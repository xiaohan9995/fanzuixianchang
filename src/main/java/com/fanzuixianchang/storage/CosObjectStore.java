package com.fanzuixianchang.storage;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.*;
import com.qcloud.cos.exception.CosServiceException;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Date;
/** SDK is only used server-side; private ACL is explicit on every new object. */
public final class CosObjectStore implements ObjectStore {
 private final COSClient client;private final String bucket;
 public CosObjectStore(COSClient client,String bucket){this.client=client;this.bucket=bucket;}
 public boolean enabled(){return true;}
 public void put(String key,byte[] bytes,String mime){try{var metadata=new ObjectMetadata();metadata.setContentLength(bytes.length);metadata.setContentType(mime);metadata.setContentDisposition("attachment");var request=new PutObjectRequest(bucket,key,new ByteArrayInputStream(bytes),metadata);request.setCannedAcl(CannedAccessControlList.Private);client.putObject(request);}catch(RuntimeException e){throw FileStorageService.unavailable();}}
 public byte[] get(String key,int maxBytes){try(var object=client.getObject(bucket,key);var stream=object.getObjectContent()){byte[] bytes=stream.readNBytes(maxBytes+1);if(bytes.length>maxBytes)throw FileStorageService.unavailable();return bytes;}catch(CosServiceException e){if(e.getStatusCode()==404)throw FileStorageService.missing();throw FileStorageService.unavailable();}catch(Exception e){throw FileStorageService.unavailable();}}
 public Info head(String key){try{var metadata=client.getObjectMetadata(bucket,key);return new Info(metadata.getContentType(),metadata.getContentLength());}catch(CosServiceException e){if(e.getStatusCode()==404)throw FileStorageService.missing();throw FileStorageService.unavailable();}catch(RuntimeException e){throw FileStorageService.unavailable();}}
 public String signedGet(String key,Instant expires){try{return client.generatePresignedUrl(bucket,key,Date.from(expires),HttpMethodName.GET).toExternalForm();}catch(RuntimeException e){throw FileStorageService.unavailable();}}
 public void delete(String key){try{client.deleteObject(bucket,key);}catch(CosServiceException e){if(e.getStatusCode()!=404)throw FileStorageService.unavailable();}catch(RuntimeException e){throw FileStorageService.unavailable();}}
 public void close(){client.shutdown();}
}
