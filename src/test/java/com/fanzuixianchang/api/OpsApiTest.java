package com.fanzuixianchang.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.ai.ModelGateway;
import com.fanzuixianchang.auth.AuthService;
import com.fanzuixianchang.core.Contracts;
import com.fanzuixianchang.ops.CaseFileController;
import com.fanzuixianchang.ops.CaseFileStore;
import com.fanzuixianchang.ops.CaseFileValidator;
import com.fanzuixianchang.ops.OpsAuth;
import com.fanzuixianchang.ops.OpsController;
import com.fanzuixianchang.ops.OpsMetrics;
import com.fanzuixianchang.storage.ObjectStore;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 后台鉴权的边界，走真实的路由层。
 *
 * 守的是三件事：
 *   1. 公开只读端点真的只读——写它必须 401
 *   2. 两套鉴权不串——小程序用户令牌进不了后台，管理员令牌也调不动用户接口
 *   3. 临时开放模式只开后台，不顺手把公开端点也放开
 *
 * 「首页 AI 首问建议」的端点已随功能下架移除，这里验的是案卷内容那组。
 */
class OpsApiTest {

    @TempDir Path dir;

    private static final String ADMIN = "operations-test-token-at-least-32-characters";
    private static final String USER = "local-development-token-123456";
    private static final String BEARER_ADMIN = "Bearer " + ADMIN;

    private AuthService auth() {
        var env = new MockEnvironment();
        env.setActiveProfiles("local");
        return new AuthService("", "", "", USER, "127.0.0.1", env, new ObjectMapper());
    }

    private static ModelGateway unconfiguredModel() {
        return new ModelGateway() {
            public boolean configured() { return false; }
            public Contracts.AnalysisResponse analyze(Contracts.ValidCase i) { throw new AssertionError(); }
        };
    }

    /** 图片上传用不到，给一个明确「没配对象存储」的实现。 */
    private static ObjectStore noStorage() {
        return new ObjectStore() {
            public boolean enabled() { return false; }
            public void put(String key, byte[] bytes, String mimeType) { throw new AssertionError(); }
            public Info head(String key) { throw new AssertionError(); }
            public String signedGet(String key, Instant expiresAt) { throw new AssertionError(); }
            public void delete(String key) { throw new AssertionError(); }
        };
    }

    private org.springframework.test.web.servlet.MockMvc mvc(OpsAuth opsAuth, Path dataDir) {
        var mapper = new ObjectMapper();
        var metrics = new OpsMetrics();
        var cases = new CaseFileStore(mapper,
                new com.fanzuixianchang.ops.FileOpsPersistence(mapper, dataDir),
                new CaseFileValidator(mapper));
        return MockMvcBuilders.standaloneSetup(
                        new OpsController(unconfiguredModel(), metrics),
                        new CaseFileController(cases, noStorage(), mapper))
                .setControllerAdvice(new ApiErrors())
                .addFilters(new ApiGuard(auth(), mapper, opsAuth, metrics))
                .build();
    }

    @Test
    void 公开内容只读而写它必须管理员令牌() throws Exception {
        var m = mvc(new OpsAuth(ADMIN), dir);

        // 公开端点：不带任何令牌也能读，这是小程序拉内容的路。
        m.perform(get("/api/content/cases")).andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.cases").isArray());

        // 同一条路径写它：401。公开读不等于公开写。
        m.perform(put("/api/content/cases").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 用户令牌进不了后台管理端点() throws Exception {
        var m = mvc(new OpsAuth(ADMIN), dir);

        m.perform(get("/api/ops/cases").header("Authorization", "Bearer " + USER))
                .andExpect(status().isUnauthorized());
        m.perform(get("/api/ops/feedback").header("Authorization", "Bearer " + USER))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 管理员令牌调不动小程序用户接口() throws Exception {
        var m = mvc(new OpsAuth(ADMIN), dir);

        // 两套鉴权各管各的：管理员令牌不该被当成用户身份用。
        m.perform(get("/api/foods").header("Authorization", BEARER_ADMIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 后台读得到运行时状态而指标只统计已知接口() throws Exception {
        var m = mvc(new OpsAuth(ADMIN), dir);

        m.perform(get("/api/foods").header("Authorization", BEARER_ADMIN)).andExpect(status().isUnauthorized());
        m.perform(get("/api/ops/overview").header("Authorization", BEARER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelConfigured").value(false))
                .andExpect(jsonPath("$.metricsAvailable").value(true));
        // 上一次 401 也要进统计，否则排查时看不到「谁在被拒」。
        m.perform(get("/api/ops/metrics").header("Authorization", BEARER_ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[?(@.path == '/api/foods')].requests")
                        .value(org.hamcrest.Matchers.contains(1)))
                .andExpect(jsonPath("$.routes[?(@.path == '/api/foods')].statusCounts.401")
                        .value(org.hamcrest.Matchers.contains(1)));
    }

    @Test
    void 脏内容在路由层就被拒且不落盘() throws Exception {
        var m = mvc(new OpsAuth(ADMIN), dir);
        var dirty = "{\"schemaVersion\":1,\"cases\":[{\"id\":\"a-001\"}]}";

        m.perform(put("/api/ops/cases").header("Authorization", BEARER_ADMIN)
                        .contentType("application/json")
                        .content("{\"version\":0,\"bundle\":" + dirty + "}"))
                .andExpect(status().isBadRequest());

        // 拒收之后线上仍然是空的：客户端会退回内置种子，而不是读到半包脏内容。
        m.perform(get("/api/content/cases")).andExpect(status().isOk())
                .andExpect(jsonPath("$.cases").isEmpty());
    }

    @Test
    void 临时开放模式只影响后台不影响公开端点() throws Exception {
        var m = mvc(OpsAuth.open(), dir.resolve("open"));

        m.perform(get("/api/ops/overview")).andExpect(status().isOk());
        // 公开端点在开放模式下依然只读。
        m.perform(put("/api/content/cases").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
