package com.fanzuixianchang.auth;

import com.fanzuixianchang.account.UserDataStore;
import com.fanzuixianchang.core.Problem;
import com.fanzuixianchang.core.SessionTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/** CloudRun identity comes from WeChat-injected request headers; no wx.login exchange is needed. */
@Service
public final class AuthService {
    private final String appId, expectedEnv, devToken;
    private final boolean local;
    private final IdentityHasher identities;
    private final UserDataStore users;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(@Value("${app.wechat.app-id:}") String appId,
                       @Value("${app.auth.cloudrun-env-id:}") String expectedEnv,
                       @Value("${app.auth.identity-secret:}") String identitySecret,
                       @Value("${app.auth.dev-token:}") String devToken,
                       @Value("${server.address:127.0.0.1}") String address,
                       Environment env, UserDataStore users) {
        this.appId = appId;
        this.expectedEnv = expectedEnv;
        this.devToken = devToken;
        this.users = users;
        this.local = env.acceptsProfiles(Profiles.of("local"));
        if (local && !devToken.isBlank() && !(address.equals("127.0.0.1") || address.equals("::1") || address.equals("localhost")))
            throw new IllegalArgumentException("Local dev token requires loopback bind address");
        this.identities = identitySecret.length() >= 32 ? new IdentityHasher(identitySecret) : null;
    }

    /** Compatibility constructor for local API harnesses; production uses the injected CloudRun configuration above. */
    public AuthService(String appId, String appSecret, String signingSecret, String devToken,
                       String address, Environment env, ObjectMapper mapper) {
        this(appId, "", "", devToken, address, env, null);
    }

    /** Returns the HMAC identity. CloudRun's edge injects these headers for callContainer requests. */
    public String authenticate(String authorization, String remote, String openid, String requestAppId, String requestEnv) {
        if (local && authorization != null && authorization.startsWith("Bearer ")
                && SessionTokens.allowDevToken(true, remote, devToken, authorization.substring(7))) {
            return identities == null ? "local-developer" : identities.hash("local-developer");
        }
        if (openid == null || openid.isBlank() || openid.length() > 200) throw unauthorized();
        if (appId.isBlank() || expectedEnv.isBlank() || identities == null) {
            throw new Problem(503, "CLOUD_AUTH_NOT_CONFIGURED", "云托管登录校验尚未配置，请检查小程序 AppID、云环境和用户身份密钥。");
        }
        if (!constantEquals(appId, requestAppId) || !constantEquals(expectedEnv, requestEnv)) throw unauthorized();
        return identities.hash(openid);
    }

    /** Explicit account entry creates or restores the persistent account after identity validation. */
    public void bootstrap(String identityHash) {
        if (users == null || appId.isBlank()) throw new Problem(503, "USER_STORE_UNAVAILABLE", "账号服务暂不可用，请稍后重试。");
        users.createOrGetUser(appId, identityHash);
    }

    public String identityHash(String value) {
        if (identities == null) throw new Problem(503, "CLOUD_AUTH_NOT_CONFIGURED", "用户身份密钥尚未配置。");
        return identities.hash(value);
    }

    public String appId() { return appId; }
    private static boolean constantEquals(String expected, String actual) {
        if (actual == null) return false;
        return java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static Problem unauthorized() { return new Problem(401, "UNAUTHORIZED", "微信身份校验失败，请确认小程序已关联云托管环境后重试。"); }
}
