package com.fanzuixianchang.ops;

import com.fanzuixianchang.core.Problem;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 管理员令牌的边界。
 *
 * 这三条各自对应一种真实的出错方式：
 *   1. 没配令牌时必须拒绝——不能因为「还没配」就默认放行
 *   2. 用户令牌不能当管理员令牌用——两套鉴权不能串
 *   3. 临时开放模式要真的放行——否则运维会被自己的安全措施锁在外面
 */
class OpsAuthTest {

    @Test
    void 没配令牌时一律拒绝() {
        // 空令牌意味着「还没配」，不是「不需要」。这里如果放过，
        // 一个忘记配令牌的部署就是完全敞开的。
        assertEquals(503, assertThrows(Problem.class,
                () -> new OpsAuth("").authenticate("Bearer user")).status());
        assertEquals(503, assertThrows(Problem.class,
                () -> new OpsAuth("短令牌").authenticate("Bearer 短令牌")).status(),
                "短于 32 位也算没配");
    }

    @Test
    void 用户令牌不能当管理员令牌用() {
        var auth = new OpsAuth("operations-test-token-at-least-32-characters");

        assertEquals(401, assertThrows(Problem.class,
                () -> auth.authenticate("Bearer local-development-token-123456")).status(),
                "小程序用户的令牌不该进得了后台");
        assertEquals(401, assertThrows(Problem.class, () -> auth.authenticate(null)).status());
        assertEquals(401, assertThrows(Problem.class, () -> auth.authenticate("")).status());

        assertDoesNotThrow(() -> auth.authenticate("Bearer operations-test-token-at-least-32-characters"));
    }

    @Test
    void 临时开放模式放行且不需要令牌() {
        var auth = OpsAuth.open();

        assertDoesNotThrow(() -> auth.authenticate(null));
        assertDoesNotThrow(() -> new OpsAuth("短令牌", false).authenticate(null));
        assertEquals("temporary-admin", auth.actorName());
    }
}
