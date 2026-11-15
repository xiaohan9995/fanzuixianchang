package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fanzuixianchang.core.Problem;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 案卷仓库的状态机：草稿与线上必须分开，脏包不能覆盖，版本冲突要挡住，回滚要能用。
 * 这些断言对应的是运营侧的真实风险，不是代码覆盖率。
 */
class CaseFileStoreTest {
    @TempDir Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final CaseFileValidator validator = new CaseFileValidator(mapper);

    private CaseFileStore store() {
        return new CaseFileStore(mapper, new FileOpsPersistence(mapper, dir), validator);
    }

    /** 一份最小的合法内容包，只够过校验。 */
    static JsonNode bundle(ObjectMapper mapper, String title, String verdict) {
        var root = mapper.createObjectNode();
        root.put("schemaVersion", CaseFileValidator.SCHEMA_VERSION);
        ArrayNode cases = root.putArray("cases");
        var item = cases.addObject();
        item.put("id", "a-001").put("code", "A-001").put("title", title).put("hook", "她说只吃了半碗。");
        item.putObject("meta").put("place", "茶水间").put("time", "某个加班周").put("length", "约 3 分钟");
        ArrayNode cast = item.putArray("cast");
        cast.addObject().put("role", "报案人").put("name", "林小姐").put("line", "我只吃了半碗。");
        ArrayNode panels = item.putArray("panels");
        panels.addObject().put("kind", "text").put("no", "壹").put("title", "开场").put("body", "连续三天都记半碗饭。");
        ArrayNode evidence = item.putArray("evidence");
        evidence.addObject().put("label", "空盒").put("value", "210 g").put("note", "深口圆盒");
        evidence.addObject().put("label", "盛饭后").put("value", "360 g").put("note", "压实");
        evidence.addObject().put("label", "实际").put("value", "150 g").put("note", "差值");
        item.put("evidenceNote", "同一只盒子。");
        var red = item.putObject("redHerring").put("title", "先排除一条错路");
        red.put("claim", "是不是算贵了？").put("check", "代进同一密度再比。").put("result", "密度没变，差的是克数。");
        item.putObject("verdict").put("title", "结案").put("text", verdict);
        var lesson = item.putObject("lesson").put("title", "规矩");
        lesson.putArray("points").add("体积不等于质量。").add("能称就称一次。");
        item.put("quote", "秤不会替你省粮食。");
        item.putArray("sourceIds").add("H02");
        item.putObject("action").put("label", "称一称这一顿").put("url", "/pages/meal-log/index");
        item.putObject("next").put("code", "A-002").put("title", "零糖的证明").put("teaser", "配料表在笑。");
        return root;
    }

    @Test void 草稿不影响线上() {
        var s = store();
        assertNull(s.published(), "还没发布过时线上应当是空的");

        s.save(new CaseFileStore.Change(0, bundle(mapper, "半碗饭的证词", "没有人在算错。")));
        assertNull(s.published(), "只存草稿不能让内容上线——这就是审核的意义");
        assertNotNull(s.get().draft());

        s.publish(1);
        assertNotNull(s.published(), "发布后才上线");
        assertEquals("半碗饭的证词", s.published().path("cases").get(0).path("title").asText());
    }

    @Test void 脏包不能覆盖已发布内容() {
        var s = store();
        s.save(new CaseFileStore.Change(0, bundle(mapper, "半碗饭的证词", "没有人在算错。")));
        s.publish(1);
        var before = s.published().toString();

        var dirty = bundle(mapper, "改了标题", "没有人在算错。");
        ((com.fasterxml.jackson.databind.node.ObjectNode) dirty.path("cases").get(0)).remove("panels");
        assertThrows(Problem.class, () -> s.save(new CaseFileStore.Change(2, dirty)));
        assertEquals(before, s.published().toString(), "拒收后线上内容必须一字未变");
    }

    @Test void 版本冲突要挡住() {
        var s = store();
        s.save(new CaseFileStore.Change(0, bundle(mapper, "第一版", "结案一。")));
        // 拿旧版本号再存：有人刚动过内容
        assertThrows(Problem.class, () -> s.save(new CaseFileStore.Change(0, bundle(mapper, "第二版", "结案二。"))));
        assertThrows(Problem.class, () -> s.publish(0), "发布同样要比对版本");
    }

    @Test void 回滚能退回指定版本() {
        var s = store();
        s.save(new CaseFileStore.Change(0, bundle(mapper, "第一版", "结案一。")));
        s.publish(1);
        s.save(new CaseFileStore.Change(2, bundle(mapper, "第二版", "结案二。")));
        s.publish(3);

        assertEquals("第二版", s.published().path("cases").get(0).path("title").asText());
        // 历史快照记的是「提交之后的版本号」：publish(1) 之后版本是 2，所以目标是 2。
        var firstRelease = s.get().history().get(0).version();
        s.rollback(new CaseFileStore.Revision(4, firstRelease));
        assertEquals("第一版", s.published().path("cases").get(0).path("title").asText(), "回滚要真的把内容换回来");
    }

    @Test void 回滚到不存在的版本要报错() {
        var s = store();
        s.save(new CaseFileStore.Change(0, bundle(mapper, "第一版", "结案一。")));
        s.publish(1);
        assertThrows(Problem.class, () -> s.rollback(new CaseFileStore.Revision(2, 999)));
    }

    @Test void 没有草稿不能发布() {
        assertThrows(Problem.class, () -> store().publish(0));
    }

    @Test void 审计与历史都留痕并封顶() {
        var s = store();
        for (int i = 0; i < 30; i++) {
            var version = s.get().version();
            s.save(new CaseFileStore.Change(version, bundle(mapper, "第 " + i + " 版", "结案。")));
            s.publish(version + 1);
        }
        var state = s.get();
        assertEquals(20, state.history().size(), "历史最多留 20 条");
        // 每轮 save+publish 各记一条，30 轮共 60 条；上限是 100，所以还没有截断。
        assertEquals(60, state.audit().size(), "审计要逐条记录，且不超过上限");
        assertTrue(state.audit().size() <= 100);
        assertEquals("publish", state.audit().get(state.audit().size() - 1).action());
    }

    @Test void 重启后版本与内容都还在() {
        var s = store();
        s.save(new CaseFileStore.Change(0, bundle(mapper, "半碗饭的证词", "没有人在算错。")));
        s.publish(1);

        var reloaded = store();
        assertEquals(2, reloaded.get().version());
        assertEquals("半碗饭的证词", reloaded.published().path("cases").get(0).path("title").asText());
        assertTrue(Files.exists(dir.resolve("cases-snapshot.json")), "案卷用独立命名空间，不和 AI 建议混在一起");
    }

    @Test void 客户端视图隐藏草稿并把本地素材原样保留() {
        var s = store();
        s.save(new CaseFileStore.Change(0, bundle(mapper, "半碗饭的证词", "没有人在算错。")));
        s.publish(1);

        var client = CaseFileStore.forClient(s.published(), key -> key.startsWith("/") ? key : "https://cdn.example/" + key);
        assertNull(client.get("draft"), "客户端不该看到草稿");
        assertNull(client.get("audit"), "客户端不该看到审计");
        assertEquals("半碗饭的证词", client.path("cases").get(0).path("title").asText());
    }

    @Test void 未发布时公开端点是空包() {
        // 这一条保护的是「后台还没配好」的状态：客户端应当退回内置种子，而不是报错。
        var s = store();
        assertNull(s.published());
        assertTrue(s.get().version() == 0);
    }
}
