package com.fanzuixianchang.storage;
import com.fanzuixianchang.core.Problem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
@Service
public final class FileStorageService {
 public static final int MAX_BYTES=2*1024*1024;
 public record Upload(String mimeType,String base64,Boolean consent){}
 public record Stored(String id,String mimeType,long sizeBytes){}
 public record Access(String id,String mimeType,long sizeBytes,String url,String expiresAt){}
 private final ObjectStore store;private final Clock clock;
 @org.springframework.beans.factory.annotation.Autowired public FileStorageService(ObjectStore store){this(store,Clock.systemUTC());}
 FileStorageService(ObjectStore store,Clock clock){this.store=store;this.clock=clock;}
 public Stored upload(String subject,Upload input){
  ensureEnabled();String owner=owner(subject);
  if(input==null||!Boolean.TRUE.equals(input.consent())||!Set.of("image/jpeg","image/png").contains(input.mimeType()==null?"":input.mimeType()))throw invalid("请确认同意上传，并选择JPEG或PNG图片。");
  if(input.base64()==null||input.base64().isEmpty()||input.base64().length()>((MAX_BYTES+2)/3)*4)throw invalid("图片需为2MiB以内的有效JPEG或PNG。");
  byte[] bytes;try{bytes=Base64.getDecoder().decode(input.base64());}catch(IllegalArgumentException e){throw invalid("图片编码无效，请重新选择图片。");}
  if(bytes.length==0||bytes.length>MAX_BYTES||!matches(bytes,input.mimeType()))throw invalid("图片格式与内容不一致，或超过2MiB。");
  String id=UUID.randomUUID().toString();try{store.put(key(owner,id),bytes,input.mimeType());return new Stored(id,input.mimeType(),bytes.length);}catch(Problem e){throw e;}catch(RuntimeException e){throw unavailable();}
 }
 public Access access(String subject,String id){ensureEnabled();String key=key(owner(subject),id);try{var info=store.head(key);if(info==null)throw missing();if(!Set.of("image/jpeg","image/png").contains(info.mimeType()==null?"":info.mimeType())||info.sizeBytes()<0||info.sizeBytes()>MAX_BYTES)throw unavailable();Instant expires=clock.instant().plusSeconds(300);String url=store.signedGet(key,expires);if(url==null||!url.startsWith("https://"))throw unavailable();return new Access(id,info.mimeType(),info.sizeBytes(),url,expires.toString());}catch(Problem e){throw e;}catch(RuntimeException e){throw unavailable();}}
 public void delete(String subject,String id){ensureEnabled();String key=key(owner(subject),id);try{store.delete(key);}catch(Problem e){throw e;}catch(RuntimeException e){throw unavailable();}}
 private void ensureEnabled(){if(!store.enabled())throw new Problem(503,"STORAGE_NOT_CONFIGURED","文件存储尚未配置，已有本机图片不会自动上传。");}
 private static String owner(String subject){if(subject==null||subject.isBlank())throw new Problem(401,"UNAUTHORIZED","请先登录后操作文件。");try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static String key(String owner,String id){try{if(id==null||!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw invalid("文件编号无效。");}return "private/users/"+owner+"/"+id;}
 private static boolean matches(byte[] b,String mime){if(mime.equals("image/jpeg"))return b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255;byte[] png={(byte)137,80,78,71,13,10,26,10};if(b.length<8)return false;for(int i=0;i<8;i++)if(b[i]!=png[i])return false;return true;}
 private static Problem invalid(String message){return new Problem(400,"INVALID_INPUT",message);}
 static Problem unavailable(){return new Problem(503,"STORAGE_UNAVAILABLE","文件存储暂不可用，请稍后重试。");}
 static Problem missing(){return new Problem(404,"FILE_NOT_FOUND","文件不存在或不属于当前账号。");}
}
