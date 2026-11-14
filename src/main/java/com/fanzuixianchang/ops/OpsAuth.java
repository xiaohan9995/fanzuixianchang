package com.fanzuixianchang.ops;
import com.fanzuixianchang.core.Problem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public final class OpsAuth {
 private final String token;
 public OpsAuth(@Value("${app.ops.admin-token:}") String token){this.token=token;}
 public void authenticate(String header){
  if(token.length()<32)throw new Problem(503,"OPS_DISABLED","运营管理尚未配置。");
  if(header==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),header.getBytes(StandardCharsets.UTF_8)))throw new Problem(401,"UNAUTHORIZED","管理员凭证无效。");
 }
}
