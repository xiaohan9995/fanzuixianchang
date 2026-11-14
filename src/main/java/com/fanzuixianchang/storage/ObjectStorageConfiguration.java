package com.fanzuixianchang.storage;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.*;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
@Configuration
public class ObjectStorageConfiguration {
 @Bean(destroyMethod="close") public ObjectStore objectStore(@Value("${app.storage.enabled:false}") boolean enabled,@Value("${app.storage.cos.bucket:}") String bucket,@Value("${app.storage.cos.region:}") String region,@Value("${app.storage.cos.secret-id:}") String secretId,@Value("${app.storage.cos.secret-key:}") String secretKey,@Value("${app.storage.cos.session-token:}") String token){
  if(!enabled)return new ObjectStore(){public boolean enabled(){return false;}public void put(String key,byte[] bytes,String mime){throw FileStorageService.unavailable();}public Info head(String key){throw FileStorageService.unavailable();}public String signedGet(String key,java.time.Instant expires){throw FileStorageService.unavailable();}public void delete(String key){throw FileStorageService.unavailable();}};
  if(!bucket.matches("[a-z0-9][a-z0-9-]*-[0-9]+")||!region.matches("[a-z]+-[a-z0-9]+(?:-[a-z0-9]+)*")||secretId.isBlank()||secretKey.isBlank())throw new IllegalArgumentException("COS enabled requires bucket, region and server credentials");
  COSCredentials credentials=token.isBlank()?new BasicCOSCredentials(secretId,secretKey):new BasicSessionCredentials(secretId,secretKey,token);
  var config=new ClientConfig(new Region(region));config.setHttpProtocol(HttpProtocol.https);config.setConnectionTimeout(10000);config.setSocketTimeout(20000);config.setMaxErrorRetry(1);
  return new CosObjectStore(new COSClient(credentials,config),bucket);
 }
}
