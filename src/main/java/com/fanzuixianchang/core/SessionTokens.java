package com.fanzuixianchang.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Opaque, HMAC-signed short-lived sessions; session_key and openid never reach clients. */
public final class SessionTokens {
    public record Issued(String token,String expiresAt) {}
    private final byte[] secret;private final Clock clock;
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    public SessionTokens(String secret,Clock clock){
        if(secret==null||secret.length()<32)throw new IllegalArgumentException("SESSION_SIGNING_SECRET must have at least 32 characters");
        this.secret=secret.getBytes(StandardCharsets.UTF_8);this.clock=clock;
    }
    public Issued issue(String openid){
        if(openid==null||openid.isBlank()||openid.length()>200)throw Problem.input("登录身份无效。");
        Instant expires=clock.instant().plus(Duration.ofHours(2));
        byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);
        String subject=ENCODER.encodeToString(hmac(openid));
        String payload="v1."+subject+"."+expires.getEpochSecond()+"."+ENCODER.encodeToString(nonce);
        return new Issued(payload+"."+ENCODER.encodeToString(hmac(payload)),expires.toString());
    }
    public Optional<String> verify(String token){
        if(token==null||token.length()>1000)return Optional.empty();
        String[] parts=token.split("\\.",-1);if(parts.length!=5||!parts[0].equals("v1"))return Optional.empty();
        try{
            String payload=String.join(".",Arrays.copyOf(parts,4));
            if(!MessageDigest.isEqual(hmac(payload),Base64.getUrlDecoder().decode(parts[4])))return Optional.empty();
            long expiry=Long.parseLong(parts[2]);
            if(expiry<=clock.instant().getEpochSecond()||expiry>clock.instant().plus(Duration.ofHours(2)).getEpochSecond())return Optional.empty();
            if(Base64.getUrlDecoder().decode(parts[1]).length!=32||Base64.getUrlDecoder().decode(parts[3]).length!=16)return Optional.empty();
            return Optional.of(parts[1]);
        }catch(RuntimeException e){return Optional.empty();}
    }
    public static boolean allowDevToken(boolean localProfile,String remote,String configured,String candidate){
        if(!localProfile||!Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(remote)||configured==null||configured.length()<24||candidate==null)return false;
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),candidate.getBytes(StandardCharsets.UTF_8));
    }
    private byte[] hmac(String value){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}
        catch(GeneralSecurityException e){throw new IllegalStateException("HMAC unavailable",e);}
    }
}
