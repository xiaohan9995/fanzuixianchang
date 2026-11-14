package com.fanzuixianchang.account;

import com.fanzuixianchang.core.Problem;
import com.fanzuixianchang.storage.ObjectStore;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;

/** Private photo registry and durable deletion queue, sharing meal transactions. */
final class MealPhotos {
 static final int MAX_BYTES=2*1024*1024;
 static final String PURPOSE="meal_photo_sync", POLICY="meal-photos-v1";
 private final ObjectStore objects; private final ObjectMapper mapper;
 MealPhotos(ObjectStore objects,ObjectMapper mapper){this.objects=objects;this.mapper=mapper;}
 boolean enabled(){return objects!=null&&objects.enabled();}
 void requireEnabled(){if(!enabled())throw new Problem(503,"STORAGE_NOT_CONFIGURED","餐图云存储尚未配置，本机图片仍保留。");}
 static void lockUser(Connection c,String user)throws SQLException{try(var q=c.prepareStatement("SELECT status FROM fanzui_users WHERE user_id=? FOR UPDATE")){q.setString(1,user);try(var r=q.executeQuery()){if(!r.next()||!"active".equals(r.getString(1)))throw new Problem(401,"UNAUTHORIZED","账号不可用。");}}}
 static boolean consent(Connection c,String user,String purpose)throws SQLException{try(var q=c.prepareStatement("SELECT granted FROM fanzui_user_consents WHERE user_id=? AND purpose=?")){q.setString(1,user);q.setString(2,purpose);try(var r=q.executeQuery()){return r.next()&&r.getBoolean(1);}}}
 static void requireConsent(Connection c,String user)throws SQLException{if(!consent(c,user,PURPOSE)||!consent(c,user,"meal_cloud_sync"))throw new Problem(403,"PHOTO_CONSENT_REQUIRED","请先开启饭案同步，并单独同意保存餐食照片。");}
 static byte[] decode(String mime,String base64){
  if(base64==null||base64.length()>((MAX_BYTES+2)/3)*4)throw Problem.input("餐图需为2MiB以内的JPEG、PNG或WebP。");
  byte[] b;try{b=Base64.getDecoder().decode(base64);}catch(IllegalArgumentException e){throw Problem.input("图片编码无效。");}
  return validateBytes(mime,b);
 }
 static byte[] validateBytes(String mime,byte[] b){
  if(!Set.of("image/jpeg","image/png","image/webp").contains(mime==null?"":mime)||b==null||b.length==0||b.length>MAX_BYTES||!matches(mime,b))throw Problem.input("图片格式与内容不一致或超过2MiB。");return b;
 }
 static boolean matches(String mime,byte[] b){
  if(mime.equals("image/jpeg"))return b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255;
  if(mime.equals("image/webp"))return b.length>=12&&b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='E'&&b[10]=='B'&&b[11]=='P';
  byte[] png={(byte)137,80,78,71,13,10,26,10};return b.length>=8&&Arrays.equals(Arrays.copyOf(b,8),png);
 }
 String stage(Connection c,String user,String record,String mime,int size)throws SQLException{
  requireEnabled();lockUser(c,user);requireConsent(c,user);
  if(record==null||!record.matches("[A-Za-z0-9_-]{1,120}"))throw Problem.input("饭案编号无效。");
  try(var q=c.prepareStatement("SELECT COUNT(*) FROM fanzui_meal_photos WHERE user_id=?")){q.setString(1,user);try(var r=q.executeQuery()){r.next();if(r.getInt(1)>=3000)throw new Problem(409,"PHOTO_LIMIT","餐图数量已达上限，请清理不用的云端饭案。");}}
  String id=UUID.randomUUID().toString();
  try(var q=c.prepareStatement("INSERT INTO fanzui_meal_photos(file_id,user_id,record_id,object_key,mime_type,size_bytes,state) VALUES(?,?,?,?,?,?,'staged')")){q.setString(1,id);q.setString(2,user);q.setString(3,record);q.setString(4,"private/users/"+user+"/meals/"+id);q.setString(5,mime);q.setInt(6,size);q.executeUpdate();}return id;
 }
 Map<String,Object> upload(Connection c,String user,String id,byte[] bytes)throws SQLException{
  lockUser(c,user);requireConsent(c,user);
  try(var q=c.prepareStatement("SELECT object_key,mime_type,state FROM fanzui_meal_photos WHERE file_id=? AND user_id=? FOR UPDATE")){q.setString(1,id);q.setString(2,user);try(var r=q.executeQuery()){if(!r.next()||!r.getString(3).equals("staged"))throw missing();objects.put(r.getString(1),bytes,r.getString(2));String mime=r.getString(2);try(var u=c.prepareStatement("UPDATE fanzui_meal_photos SET state='uploaded' WHERE file_id=?")){u.setString(1,id);u.executeUpdate();}return Map.of("fileId",id,"mimeType",mime,"sizeBytes",bytes.length);}}
 }
 Map<String,Object> read(Connection c,String user,String id)throws SQLException{
  requireEnabled();try(var q=c.prepareStatement("SELECT object_key,mime_type,size_bytes FROM fanzui_meal_photos WHERE file_id=? AND user_id=? AND state IN ('uploaded','linked') FOR UPDATE")){q.setString(1,id);q.setString(2,user);try(var r=q.executeQuery()){if(!r.next())throw missing();String mime=r.getString(2);byte[] bytes=objects.get(r.getString(1),MAX_BYTES);if(bytes.length!=r.getInt(3)||!matches(mime,bytes))throw new Problem(503,"STORAGE_UNAVAILABLE","餐图读取失败，请重试。");return Map.of("fileId",id,"mimeType",mime,"base64",Base64.getEncoder().encodeToString(bytes));}}
 }
 void attach(Connection c,String user,String record,ObjectNode payload)throws SQLException{
  // Old clients do not know cloudImages; preserve existing attached photos in that case.
  if(!payload.has("cloudImages")){var refs=mapper.createArrayNode();try(var q=c.prepareStatement("SELECT file_id,mime_type FROM fanzui_meal_photos WHERE user_id=? AND record_id=? AND state='linked' ORDER BY file_id")){q.setString(1,user);q.setString(2,record);try(var r=q.executeQuery()){while(r.next())refs.addObject().put("fileId",r.getString(1)).put("mimeType",r.getString(2));}}payload.set("cloudImages",refs);}
  JsonNode refs=payload.get("cloudImages");if(!refs.isArray()||refs.size()>3)throw Problem.input("每份饭案最多同步3张照片。");
  Set<String> keep=new HashSet<>();for(JsonNode ref:refs){String id=ref.path("fileId").asText("");if(!id.matches("[a-f0-9-]{36}")||!keep.add(id))throw Problem.input("照片编号无效或重复。");
   try(var q=c.prepareStatement("SELECT mime_type,state FROM fanzui_meal_photos WHERE file_id=? AND user_id=? AND record_id=? FOR UPDATE")){q.setString(1,id);q.setString(2,user);q.setString(3,record);try(var r=q.executeQuery()){if(!r.next()||!Set.of("uploaded","linked").contains(r.getString(2))||!r.getString(1).equals(ref.path("mimeType").asText()))throw missing();if(r.getString(2).equals("uploaded"))requireConsent(c,user);}}
  }
  List<String> previous=new ArrayList<>();try(var q=c.prepareStatement("SELECT file_id FROM fanzui_meal_photos WHERE user_id=? AND record_id=? AND state='linked'")){q.setString(1,user);q.setString(2,record);try(var r=q.executeQuery()){while(r.next())previous.add(r.getString(1));}}
  for(String id:previous)if(!keep.contains(id))state(c,id,"deleting");for(String id:keep)state(c,id,"linked");
 }
 void queueDelete(Connection c,String user,String record)throws SQLException{try(var q=c.prepareStatement("UPDATE fanzui_meal_photos SET state='deleting' WHERE user_id=?"+(record==null?"":" AND record_id=?"))){q.setString(1,user);if(record!=null)q.setString(2,record);q.executeUpdate();}}
 private void state(Connection c,String id,String state)throws SQLException{try(var q=c.prepareStatement("UPDATE fanzui_meal_photos SET state=? WHERE file_id=?")){q.setString(1,state);q.setString(2,id);q.executeUpdate();}}
 // Row locks serialize cleanup against uploads, reads and linking. Failed deletions remain retryable.
 int cleanup(Connection c)throws SQLException{
  if(!enabled())return 0;List<String> candidates=new ArrayList<>();String eligible="(state='deleting' OR (state IN ('staged','uploaded') AND created_at<?))";Timestamp cutoff=Timestamp.from(java.time.Instant.now().minusSeconds(86400));
  try(var q=c.prepareStatement("SELECT file_id FROM fanzui_meal_photos WHERE "+eligible+" ORDER BY created_at LIMIT 30")){q.setTimestamp(1,cutoff);try(var r=q.executeQuery()){while(r.next())candidates.add(r.getString(1));}}
  int deleted=0;for(String id:candidates){try(var q=c.prepareStatement("SELECT object_key FROM fanzui_meal_photos WHERE file_id=? AND "+eligible+" FOR UPDATE")){q.setString(1,id);q.setTimestamp(2,cutoff);try(var r=q.executeQuery()){if(!r.next())continue;objects.delete(r.getString(1));try(var d=c.prepareStatement("DELETE FROM fanzui_meal_photos WHERE file_id=?")){d.setString(1,id);d.executeUpdate();}deleted++;}}c.commit();}return deleted;
 }
 static Problem missing(){return new Problem(404,"FILE_NOT_FOUND","餐图不存在、已删除或不属于这份饭案。");}
}
