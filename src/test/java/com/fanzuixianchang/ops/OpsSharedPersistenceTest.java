package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作流仓库（反馈与提案）在共享底座上的行为。
 *
 * 案卷那侧的同类语义已迁到 {@link CaseFileOptimisticLockTest}，
 * 这里只保留 OpsWorkStore 的部分。
 */
class OpsSharedPersistenceTest {

    private static final class Memory implements OpsPersistence {
        final Map<String, Snapshot> data = new HashMap<>();
        boolean fail = false;
        boolean conflict = false;

        public synchronized Snapshot load(String key) { return data.get(key); }

        public synchronized void save(String key, long expected, String json) {
            if (fail) throw new Problem(503, "STORE_UNAVAILABLE", "offline");
            var old = data.get(key);
            long actual = old == null ? -1 : old.revision();
            if (conflict || actual != expected) throw new Problem(409, "VERSION_CONFLICT", "conflict");
            data.put(key, new Snapshot(expected + 1, json));
        }
    }

    @Test
    void 多实例读最新且失败不留下幽灵条目() {
        var p = new Memory();
        var m = new ObjectMapper();
        var a = new OpsWorkStore(m, p);
        var b = new OpsWorkStore(m, p);

        var first = a.createFeedback(new OpsWorkStore.FeedbackInput("other", "已有反馈", true));
        assertEquals(first.id(), b.feedback().getFirst().id(), "第二个实例该读到 a 刚写的那条");

        p.fail = true;
        assertEquals(503, assertThrows(Problem.class,
                () -> b.createFeedback(new OpsWorkStore.FeedbackInput("other", "失败反馈", true))).status());
        p.fail = false;

        // 失败的那次不能留下任何痕迹——不能出现在列表里，也不能进审计。
        assertEquals(1, a.feedback().size());
        assertEquals(1, b.feedback().size());
        assertEquals(1, b.audit().size(), "失败的操作不该记进审计");

        b.updateFeedback(first.id(), new OpsWorkStore.FeedbackChange(0, "resolved", "已核对"));
        assertEquals("resolved", new OpsWorkStore(m, p).feedback().getFirst().status(),
                "改完要落盘，重启后还在");
    }

    @Test
    void 存储读不出来时不交出默认数据() {
        var p = new OpsPersistence() {
            public Snapshot load(String key) { throw new Problem(503, "STORE_UNAVAILABLE", "offline"); }
            public void save(String key, long expected, String json) { throw new AssertionError(); }
        };
        // 把「读不出来」当成「还没有反馈」，用户提交的东西就像从来没存在过。
        assertEquals(503, assertThrows(Problem.class, () -> new OpsWorkStore(new ObjectMapper(), p)).status());
    }
}
