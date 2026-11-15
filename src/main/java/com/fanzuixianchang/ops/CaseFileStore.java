package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fanzuixianchang.core.Problem;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 侦探案卷的内容仓库：草稿、发布、回滚、审计。
 *
 * 和 OpsStore 是同一个套路（那份管的是 AI 首问建议），但案卷的内容形状复杂得多，
 * 所以单独一个 Store，复用同一个 OpsPersistence（namespace="cases"），
 * 不新建表、不加迁移脚本。
 *
 * 「审核」在这套里就是 publish 这个动作本身：save 只动草稿，publish 才让内容上线。
 * 单人流程已确认，所以没有第二个人签字的环节，但每次动作都进审计，事后可追。
 */
@Component
public final class CaseFileStore {
    /** 一次发布或草稿快照。 */
    public record Snapshot(long version, String at, JsonNode bundle) {}
    public record Audit(String action, long version, String at) {}
    /** 后台看到的完整状态：草稿与线上分开，历史与审计只留最近若干条。 */
    public record State(long version, JsonNode draft, JsonNode published, List<Snapshot> history, List<Audit> audit) {}
    /** 存草稿：带上客户端看到的版本号，防止覆盖别人刚存的内容。 */
    public record Change(long version, JsonNode bundle) {}
    /** 发布或回滚：version 是当前版本，targetVersion 只在回滚时用。 */
    public record Revision(long version, long targetVersion) {}

    private static final String NAMESPACE = "cases";
    private static final int MAX_HISTORY = 20;
    private static final int MAX_AUDIT = 100;

    private final ObjectMapper mapper;
    private final OpsPersistence persistence;
    private final CaseFileValidator validator;
    private State state;
    private long persistedRevision = -1;

    @Autowired
    public CaseFileStore(ObjectMapper mapper, OpsPersistence persistence, CaseFileValidator validator) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.validator = validator;
        reload();
    }

    private void reload() {
        var snapshot = persistence.load(NAMESPACE);
        // 首次部署：还没存过任何东西。这是正常状态，不是损坏——线上没有内容，
        // 客户端会退回内置种子。所以这里直接接受空状态，不要走下面的校验。
        if (snapshot == null) {
            state = empty();
            persistedRevision = -1;
            return;
        }
        try {
            State loaded = mapper.readValue(snapshot.json(), State.class);
            // 有快照之后，draft 与 published 可以分别是 null（只存了草稿还没发布，
            // 或者只发布过还没改草稿），但列表字段和版本号必须完好。
            if (loaded.history() == null || loaded.audit() == null
                    || loaded.history().size() > MAX_HISTORY || loaded.audit().size() > MAX_AUDIT
                    || loaded.version() < 0) {
                throw new IOException("Invalid case store");
            }
            state = loaded;
            persistedRevision = snapshot.revision();
        } catch (IOException e) {
            throw new Problem(503, "STORE_UNAVAILABLE", "案卷内容读取失败，请稍后重试。");
        }
    }

    /** 首次部署：空内容包。线上还没发布过任何案卷，客户端会退回内置种子。 */
    private static State empty() {
        return new State(0, null, null, List.of(), List.of());
    }

    public synchronized State get() {
        reload();
        return state;
    }

    /** 只读线上内容：给客户端用，不含草稿与审计。 */
    public synchronized JsonNode published() {
        reload();
        return present(state.published());
    }

    /** Jackson 把 JSON null 读成 NullNode 而不是 Java null，调用方不该关心这个区别。 */
    private static JsonNode present(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    public synchronized State save(Change change) {
        reload();
        check(change.version());
        validator.require(change.bundle());
        return commit("save", change.bundle(), present(state.published()), false);
    }

    public synchronized State publish(long version) {
        reload();
        check(version);
        if (present(state.draft()) == null) throw new Problem(400, "INVALID_INPUT", "没有可发布的草稿。");
        // 发布前再校验一次：草稿可能是在旧规则下存进去的。
        validator.require(present(state.draft()));
        return commit("publish", present(state.draft()), present(state.draft()), true);
    }

    public synchronized State rollback(Revision revision) {
        reload();
        check(revision.version());
        var target = state.history().stream()
                .filter(item -> item.version() == revision.targetVersion())
                .findFirst()
                .orElseThrow(() -> new Problem(400, "INVALID_INPUT", "找不到可回滚的版本。"));
        return commit("rollback", target.bundle(), target.bundle(), true);
    }

    private void check(long version) {
        if (state.version() != version) {
            throw new Problem(409, "VERSION_CONFLICT", "案卷已更新，请刷新后再操作。");
        }
    }

    private State commit(String action, JsonNode draft, JsonNode published, boolean release) {
        long version = state.version() + 1;
        String now = Instant.now().toString();

        var history = new ArrayList<>(state.history());
        if (release && published != null) history.add(new Snapshot(version, now, published));
        while (history.size() > MAX_HISTORY) history.remove(0);

        var audit = new ArrayList<>(state.audit());
        audit.add(new Audit(action, version, now));
        while (audit.size() > MAX_AUDIT) audit.remove(0);

        var next = new State(version, draft, published, List.copyOf(history), List.copyOf(audit));
        try {
            persistence.save(NAMESPACE, persistedRevision, mapper.writeValueAsString(next));
        } catch (IOException e) {
            throw new Problem(503, "STORE_UNAVAILABLE", "保存失败，原版本未改变。");
        }
        persistedRevision++;
        state = next;
        return state;
    }

    /**
     * 把内容里的图片 key 换成可访问地址，然后只留客户端需要的字段。
     * 客户端不该看到草稿、审计，也不该知道存储实现。
     */
    public static JsonNode forClient(JsonNode bundle, java.util.function.UnaryOperator<String> resolveImage) {
        if (bundle == null) return null;
        var copy = bundle.deepCopy();
        if (copy.path("cases").isArray()) {
            for (var item : copy.get("cases")) {
                if (!(item instanceof ObjectNode node)) continue;
                if (node.path("cover").isTextual()) node.put("cover", resolveImage.apply(node.get("cover").asText()));
                var panels = node.path("panels");
                if (panels.isArray()) {
                    for (var panel : panels) {
                        if (panel instanceof ObjectNode p && p.path("src").isTextual()) {
                            p.put("src", resolveImage.apply(p.get("src").asText()));
                        }
                    }
                }
            }
        }
        return copy;
    }
}
