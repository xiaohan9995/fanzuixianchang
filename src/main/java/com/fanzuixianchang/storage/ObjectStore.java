package com.fanzuixianchang.storage;
import java.time.Instant;
public interface ObjectStore extends AutoCloseable {
 record Info(String mimeType,long sizeBytes){}
 boolean enabled();
 void put(String key,byte[] bytes,String mimeType);
 default byte[] get(String key,int maxBytes){throw new UnsupportedOperationException("Object download unavailable");}
 Info head(String key);
 String signedGet(String key,Instant expiresAt);
 void delete(String key);
 @Override default void close(){}
}
