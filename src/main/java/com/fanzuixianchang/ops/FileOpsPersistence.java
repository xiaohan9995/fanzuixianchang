package com.fanzuixianchang.ops;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.nio.file.*;
import java.io.IOException;
/** Local-development only. Cloud deployment must select cloudbase-pg. */
public final class FileOpsPersistence implements OpsPersistence {
 private final ObjectMapper mapper;private final Path dir;
 public FileOpsPersistence(ObjectMapper mapper,Path dir){this.mapper=mapper;this.dir=dir;}
 private Path path(String key){if(!key.matches("content|workflows"))throw new IllegalArgumentException("Unknown namespace");return dir.resolve(key+"-snapshot.json");}
 public synchronized Snapshot load(String key){try{if(Files.exists(path(key)))return mapper.readValue(Files.readAllBytes(path(key)),Snapshot.class);Path legacy=dir.resolve(key+".json");return Files.exists(legacy)?new Snapshot(-1,Files.readString(legacy)):null;}catch(IOException e){throw unavailable();}}
 public synchronized void save(String key,long expected,String json){var current=load(key);if((current==null?-1:current.revision())!=expected)throw new Problem(409,"VERSION_CONFLICT","数据已更新，请刷新后重试。");try{Files.createDirectories(dir);var temp=Files.createTempFile(dir,"snapshot-",".tmp");try{mapper.writeValue(temp.toFile(),new Snapshot(expected+1,json));Files.move(temp,path(key),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}catch(IOException e){throw unavailable();}}
 private static Problem unavailable(){return new Problem(503,"STORE_UNAVAILABLE","持久存储暂不可用，请稍后重试。");}
}
