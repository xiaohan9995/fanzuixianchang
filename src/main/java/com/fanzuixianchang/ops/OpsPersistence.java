package com.fanzuixianchang.ops;
public interface OpsPersistence {
 record Snapshot(long revision,String json){}
 Snapshot load(String key);
 void save(String key,long expectedRevision,String json);
}
