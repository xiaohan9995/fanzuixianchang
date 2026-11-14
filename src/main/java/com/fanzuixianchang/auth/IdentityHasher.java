package com.fanzuixianchang.auth;

import com.fanzuixianchang.core.Problem;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Creates opaque, stable account keys without storing or exposing a WeChat OpenID. */
public final class IdentityHasher {
    private final byte[] secret;
    public IdentityHasher(String secret) {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("USER_IDENTITY_SECRET must have at least 32 characters");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }
    public String hash(String openid) {
        if (openid == null || openid.isBlank() || openid.length() > 200) throw new Problem(401, "UNAUTHORIZED", "账号不可用，请重新连接。 ");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(openid.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("HMAC unavailable", e); }
    }
}
