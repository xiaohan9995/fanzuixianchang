package com.fanzuixianchang.account;

import com.fanzuixianchang.core.Problem;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;

/** Private photo registry. The client uploads bytes to cloud storage and records only the file id. */
final class MealPhotos {
 static final String PURPOSE="meal_photo_sync", POLICY="meal-photos-v1";
 private final ObjectMapper mapper;
 MealPhotos(ObjectMapper mapper){this.mapper=mapper;}
 static void lockUser(Connection c,String user)throws SQLException{try(var q=c.prepareStatement("SELECT status FROM fanzui_users WHERE user_id=? FOR UPDATE")){q.setString(1,user);try(var r=q.executeQuery()){if(!r.next()||!"active".equals(r.getString(1)))throw new Problem(401,"UNAUTHORIZED","账号不可用。");}}}
 static boolean consent(Connection c,String user,String purpose)throws SQLException{try(var q=c.prepareStatement("SELECT granted FROM fanzui_user_consents WHERE user_id=? AND purpose=?")){q.setString(1,user);q.setString(2,purpose);try(var r=q.executeQuery()){return r.next()&&r.getBoolean(1);}}}
 static void requireConsent(Connection c,String user)throws SQLException{if(!consent(c,user,PURPOSE)||!consent(c,user,"meal_cloud_sync"))throw new Problem(403,"PHOTO_CONSENT_REQUIRED","请先开启饭案同步，并单独同意保存餐食照片。");}
 /** 餐图允许落在哪些目录。 */
 static final String PHOTO_FOLDER="private/meal-photos/";
 /** 早期客户端把餐图和临时识别图都写在 ai/ 下。存量记录要能读，
     新客户端已改为 private/meal-photos/；等老版本退场后删掉这一条。 */
 static final String LEGACY_FOLDER="ai/";
 /**
  * 云文件编号的校验。
  *
  * 只验 cloud:// 前缀太松：客户端可以登记任意路径，包括别人的目录
  * 或公开内容目录（content/cases/），再借自己的记录把它读出来。
  * 所以这里收窄到「餐图目录」，其余一律不收。
  */
 static String validCloudFileId(String id){
  if(id==null||!id.startsWith("cloud://")||id.length()>255||id.indexOf('\n')>=0||id.indexOf('\r')>=0)return null;
  int slash=id.indexOf('/',"cloud://".length());
  if(slash<0)return null;
  String path=id.substring(slash+1);
  if(path.indexOf("..")>=0)return null;
  if(!path.startsWith(PHOTO_FOLDER)&&!path.startsWith(LEGACY_FOLDER))return null;
  return id;
 }
 static boolean validMime(String mime){return Set.of("image/jpeg","image/png","image/webp").contains(mime==null?"":mime);}
 String register(Connection c,String user,String record,String cloudFileId,String mime)throws SQLException{
  lockUser(c,user);requireConsent(c,user);
  if(record==null||!record.matches("[A-Za-z0-9_-]{1,120}"))throw Problem.input("饭案编号无效。");
  String fileRef=validCloudFileId(cloudFileId);if(fileRef==null)throw Problem.input("餐图文件编号无效。");
  if(!validMime(mime))throw Problem.input("餐图需为 JPEG、PNG 或 WebP。");
  try(var q=c.prepareStatement("SELECT COUNT(*) FROM fanzui_meal_photos WHERE user_id=?")){q.setString(1,user);try(var r=q.executeQuery()){r.next();if(r.getInt(1)>=3000)throw new Problem(409,"PHOTO_LIMIT","餐图数量已达上限，请清理不用的云端饭案。");}}
  String id=UUID.randomUUID().toString();
  try(var q=c.prepareStatement("INSERT INTO fanzui_meal_photos(file_id,user_id,record_id,cloud_file_id,mime_type,state) VALUES(?,?,?,?,?,'uploaded')")){q.setString(1,id);q.setString(2,user);q.setString(3,record);q.setString(4,fileRef);q.setString(5,mime);q.executeUpdate();}return id;
 }
 Map<String,Object> read(Connection c,String user,String id)throws SQLException{
  try(var q=c.prepareStatement("SELECT cloud_file_id,mime_type FROM fanzui_meal_photos WHERE file_id=? AND user_id=? AND state IN ('uploaded','linked') FOR UPDATE")){q.setString(1,id);q.setString(2,user);try(var r=q.executeQuery()){if(!r.next())throw missing();return Map.of("fileId",id,"mimeType",r.getString(2),"cloudFileId",r.getString(1));}}
 }
 void attach(Connection c,String user,String record,ObjectNode payload)throws SQLException{
  // Old clients do not know cloudImages; preserve existing attached photos in that case.
  if(!payload.has("cloudImages")){var refs=mapper.createArrayNode();try(var q=c.prepareStatement("SELECT file_id,mime_type FROM fanzui_meal_photos WHERE user_id=? AND record_id=? AND state='linked' ORDER BY file_id")){q.setString(1,user);q.setString(2,record);try(var r=q.executeQuery()){while(r.next())refs.addObject().put("fileId",r.getString(1)).put("mimeType",r.getString(2));}}payload.set("cloudImages",refs);}
  JsonNode refs=payload.get("cloudImages");if(!refs.isArray()||refs.size()>3)throw Problem.input("每份饭案最多同步3张照片。");
  Set<String> keep=new HashSet<>();for(JsonNode ref:refs){String id=ref.path("fileId").asText("");if(!id.matches("[a-f0-9-]{36}")||!keep.add(id))throw Problem.input("照片编号无效或重复。");
   try(var q=c.prepareStatement("SELECT mime_type,state FROM fanzui_meal_photos WHERE file_id=? AND user_id=? AND record_id=? FOR UPDATE")){q.setString(1,id);q.setString(2,user);q.setString(3,record);try(var r=q.executeQuery()){if(!r.next()||!Set.of("uploaded","linked").contains(r.getString(2))||!r.getString(1).equals(ref.path("mimeType").asText()))throw missing();}}
  }
  List<String> previous=new ArrayList<>();try(var q=c.prepareStatement("SELECT file_id FROM fanzui_meal_photos WHERE user_id=? AND record_id=? AND state='linked'")){q.setString(1,user);q.setString(2,record);try(var r=q.executeQuery()){while(r.next())previous.add(r.getString(1));}}
  for(String id:previous)if(!keep.contains(id))state(c,id,"deleting");for(String id:keep)state(c,id,"linked");
 }
 void queueDelete(Connection c,String user,String record)throws SQLException{try(var q=c.prepareStatement("UPDATE fanzui_meal_photos SET state='deleting' WHERE user_id=?"+(record==null?"":" AND record_id=?"))){q.setString(1,user);if(record!=null)q.setString(2,record);q.executeUpdate();}}
 private void state(Connection c,String id,String state)throws SQLException{try(var q=c.prepareStatement("UPDATE fanzui_meal_photos SET state=? WHERE file_id=?")){q.setString(1,state);q.setString(2,id);q.executeUpdate();}}
 // Rows are deleted outright; the cloud file is reclaimed by the client or a lifecycle rule.
 int cleanup(Connection c)throws SQLException{
  List<String> candidates=new ArrayList<>();String eligible="(state='deleting' OR (state='uploaded' AND created_at<?))";Timestamp cutoff=Timestamp.from(java.time.Instant.now().minusSeconds(86400));
  try(var q=c.prepareStatement("SELECT file_id FROM fanzui_meal_photos WHERE "+eligible+" ORDER BY created_at LIMIT 30")){q.setTimestamp(1,cutoff);try(var r=q.executeQuery()){while(r.next())candidates.add(r.getString(1));}}
  int deleted=0;for(String id:candidates){try(var q=c.prepareStatement("DELETE FROM fanzui_meal_photos WHERE file_id=? AND "+eligible)){q.setString(1,id);q.setTimestamp(2,cutoff);deleted+=q.executeUpdate();}c.commit();}return deleted;
 }
 static Problem missing(){return new Problem(404,"FILE_NOT_FOUND","餐图不存在、已删除或不属于这份饭案。");}
}
