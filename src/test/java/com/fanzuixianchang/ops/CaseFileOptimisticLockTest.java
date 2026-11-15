package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 案卷仓库的乐观锁语义。
 *
 * 这组测试原先挂在 OpsStore（首页 AI 首问建议）上，那个功能已下架、
 * 源码待删。但被测的语义不属于那个功能——它属于 OpsPersistence 这个共用底座，
 * 案卷和反馈都靠它。删功能不能连底座一起失去守卫，所以迁到这里。
 *
 * 三条要点，都是「数据被悄悄改坏」的具体路径：
 *
 *   1. 两个实例同时开着，后存的那个不能拿旧版本号覆盖别人刚写的内容
 *   2. 底层 CAS 冲突时，内存里的状态必须还是旧的——不能一半新一半旧
 *   3. 存储读不出来时，绝不能当成「还没有数据」返回空状态
 *
 * 第 3 条最要命：把「读不出来」误当成「没内容」，接着存一版草稿，
 * 线上内容就被空包顶掉了，而且没有任何报错。
 */
class CaseFileOptimisticLockTest {

    /** 一个可以按需失败、按需冲突的内存实现，用来精确控制底层的两种异常。 */
    private static final class Memory implements OpsPersistence {
        final Map<String, Snapshot> data = new HashMap<>();
        boolean fail = false;
        boolean conflict = false;

        public synchronized Snapshot load(String key) {
            if (fail) throw new Problem(503, "STORE_UNAVAILABLE", "offline");
            return data.get(key);
        }

        public synchronized void save(String key, long expected, String json) {
            if (fail) throw new Problem(503, "STORE_UNAVAILABLE", "offline");
            var old = data.get(key);
            long actual = old == null ? -1 : old.revision();
            // conflict=true 模拟「别人刚好在这一刻写进去了」：期望值与实际不符。
            if (conflict || actual != expected) {
                throw new Problem(409, "VERSION_CONFLICT", "conflict");
            }
            data.put(key, new Snapshot(expected + 1, json));
        }
    }

    private final ObjectMapper mapper = CaseFileFixtures.MAPPER;
    private final CaseFileValidator validator = new CaseFileValidator(mapper);

    private CaseFileStore store(OpsPersistence persistence) {
        return new CaseFileStore(mapper, persistence, validator);
    }

    // ── 1. 多实例：读到最新的，且旧版本号覆盖不了 ────────────────

    @Test
    void 两个实例共享底座时读到的是最新稿() {
        var p = new Memory();
        var a = store(p);
        var b = store(p);

        a.save(new CaseFileStore.Change(0, CaseFileFixtures.bundle("甲写的标题")));

        // b 是另一个实例，但它 reload 时会读到 a 刚写的那一版。
        assertEquals("甲写的标题",
                b.get().draft().path("cases").get(0).path("title").asText(),
                "第二个实例不能守着自己那份旧内存");
    }

    @Test
    void 旧版本号写不进去也发不出去() {
        var p = new Memory();
        var a = store(p);
        var b = store(p);

        a.save(new CaseFileStore.Change(0, CaseFileFixtures.bundle("甲写的标题")));

        // b 拿着 v0 再存：这时已经是 v1 了，必须被挡。
        assertEquals(409, assertThrows(Problem.class,
                () -> b.save(new CaseFileStore.Change(0, CaseFileFixtures.bundle("乙想覆盖")))).status(),
                "过期写入要报冲突，不能静默合并");
        assertEquals(409, assertThrows(Problem.class, () -> b.publish(0)).status(),
                "发布同样要比对版本");

        // 挡完之后，a 的内容一字未变。
        assertEquals("甲写的标题",
                store(p).get().draft().path("cases").get(0).path("title").asText(),
                "被拒的写入不许留下痕迹");
    }

    // ── 2. CAS 冲突：内存状态不能半新半旧 ────────────────────────

    @Test
    void 底层冲突时不替换旧快照也不推进版本() {
        var p = new Memory();
        var s = store(p);

        // 还没写过任何东西，让底层直接报冲突。
        p.conflict = true;
        assertEquals(409, assertThrows(Problem.class,
                () -> s.save(new CaseFileStore.Change(0, CaseFileFixtures.bundle("不会生效")))).status());

        assertNull(p.data.get("cases"), "冲突时底层不该落盘任何东西");

        // 内存里的状态也得停在原处：版本 0、没有草稿、没有审计。
        p.conflict = false;
        var state = s.get();
        assertEquals(0, state.version(), "失败的操作不许推进版本号");
        assertNull(state.draft(), "失败的操作不许留下草稿");
        assertEquals(0, state.audit().size(), "失败的操作不许留下审计记录");
    }

    @Test
    void 冲突之后仍能正常写入() {
        var p = new Memory();
        var s = store(p);
        p.conflict = true;
        assertThrows(Problem.class, () -> s.save(new CaseFileStore.Change(0, CaseFileFixtures.bundle("第一次"))));

        // 冲突解除后，同一个实例要能继续工作——不能因为一次失败就卡死。
        p.conflict = false;
        s.save(new CaseFileStore.Change(0, CaseFileFixtures.bundle("第二次")));
        assertEquals("第二次", s.get().draft().path("cases").get(0).path("title").asText());
        assertEquals(1, s.get().version());
    }

    // ── 3. 读不出来 ≠ 没有数据 ──────────────────────────────────

    @Test
    void 存储读不出来时绝不返回空状态() {
        var p = new Memory();
        p.fail = true;

        // 构造时就该炸：读不出来却给一个空仓库，等于告诉调用方「线上什么都没有」。
        assertEquals(503, assertThrows(Problem.class,
                () -> store(p)).status(), "读失败要在构造时就暴露，不能拖到第一次写入");
    }

    @Test
    void 已经跑起来的实例在存储挂掉后也不装作没内容() {
        var p = new Memory();
        var s = store(p);
        s.save(new CaseFileStore.Change(0, CaseFileFixtures.bundle("线上已有的内容")));
        s.publish(1);

        p.fail = true;
        assertEquals(503, assertThrows(Problem.class, () -> s.get()).status(),
                "读失败要报错，不能默默返回内存里那份旧状态当最新");
        assertEquals(503, assertThrows(Problem.class, () -> s.published()).status(),
                "公开端点同样要报错——给客户端一个空包，客户端会退回内置种子，看起来像内容被下架了");
        assertEquals(503, assertThrows(Problem.class,
                () -> s.save(new CaseFileStore.Change(2, CaseFileFixtures.bundle("趁乱覆盖")))).status(),
                "读不出来时不许写：这时候的版本号是不可信的");

        p.fail = false;
        assertEquals("线上已有的内容",
                s.published().path("cases").get(0).path("title").asText(),
                "存储恢复后，原来的内容必须还在");
    }

    @Test
    void 真的没有数据时才给空状态() {
        // 和上面一条配对：同样的「读到 null」，一个是故障、一个是首次部署。
        // 这个区分如果做错，第一次写入就会把线上内容顶掉。
        var p = new Memory();
        var s = store(p);
        assertEquals(0, s.get().version(), "首次部署是 0，不是错误");
        assertNull(s.get().draft());
        assertNull(s.published());
        assertTrue(p.data.isEmpty(), "读的时候不该顺手写一条空记录");
    }
}
