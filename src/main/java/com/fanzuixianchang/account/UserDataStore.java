package com.fanzuixianchang.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.net.URI;
import java.sql.*;
import java.util.*;

/** User-scoped MySQL persistence. Schema is provisioned by an explicit migration. */
public final class UserDataStore {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(UserDataStore.class);
    private final ObjectMapper mapper;
    private final String url,user,password;
    private final MealPhotos photos;
    public UserDataStore(ObjectMapper mapper,String url,String user,String password){
        this.mapper=mapper;this.url=url;this.user=user;this.password=password;
        validateUrl(url);
        this.photos=new MealPhotos(mapper);
    }
    private static void validateUrl(String url){
        try{if(url==null||!url.startsWith("jdbc:mysql://"))throw new IllegalArgumentException();var u=URI.create(url.substring(5));if(u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||u.getPath()==null||!u.getPath().matches("/[A-Za-z0-9_]+"))throw new IllegalArgumentException();}
        catch(RuntimeException e){throw new IllegalArgumentException("MySQL URL must name a database without embedded credentials");}
    }
    private Connection open()throws SQLException{var p=new Properties();p.setProperty("user",user);p.setProperty("password",password);p.setProperty("connectTimeout","10000");p.setProperty("socketTimeout","20000");p.setProperty("allowLoadLocalInfile","false");p.setProperty("allowUrlInLocalInfile","false");return DriverManager.getConnection(url,p);}

    public String createOrGetUser(String appId,String identityHash){
        if(appId==null||appId.isBlank()||identityHash==null||!identityHash.matches("[A-Za-z0-9_-]{40,50}"))throw unauthorized();
        try(Connection c=open()){
            c.setAutoCommit(false);
            try(var q=c.prepareStatement("SELECT user_id FROM fanzui_wechat_identities WHERE app_id=? AND identity_hash=?")){q.setString(1,appId);q.setString(2,identityHash);try(var r=q.executeQuery()){if(r.next()){String id=r.getString(1);c.commit();return id;}}}
            String id=UUID.randomUUID().toString();
            try(var q=c.prepareStatement("INSERT INTO fanzui_users(user_id,status,created_at,updated_at) VALUES(?,'active',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))")){q.setString(1,id);q.executeUpdate();}
            try(var q=c.prepareStatement("INSERT INTO fanzui_wechat_identities(app_id,identity_hash,user_id,created_at) VALUES(?,?,?,CURRENT_TIMESTAMP(6))")){q.setString(1,appId);q.setString(2,identityHash);q.setString(3,id);q.executeUpdate();}
            c.commit();return id;
        }catch(SQLException e){if(e.getErrorCode()==1062)return findUser(appId,identityHash);throw unavailable(e);}
    }
    public String requireUser(String appId,String identityHash){if(appId==null||appId.isBlank()||identityHash==null||!identityHash.matches("[A-Za-z0-9_-]{40,50}"))throw unauthorized();return findUser(appId,identityHash);}
    private String findUser(String appId,String identityHash){try(Connection c=open();var q=c.prepareStatement("SELECT i.user_id FROM fanzui_wechat_identities i JOIN fanzui_users u ON u.user_id=i.user_id WHERE i.app_id=? AND i.identity_hash=? AND u.status='active'")){q.setString(1,appId);q.setString(2,identityHash);try(var r=q.executeQuery()){if(r.next())return r.getString(1);}}catch(SQLException e){throw unavailable(e);}throw unauthorized();}
    public String account(String appId,String identityHash){
        String id=requireUser(appId,identityHash);
        try(Connection c=open()){
            String created;try(var q=c.prepareStatement("SELECT created_at FROM fanzui_users WHERE user_id=? AND status='active'")){q.setString(1,id);try(var r=q.executeQuery()){if(!r.next())throw unauthorized();created=r.getTimestamp(1).toInstant().toString();}}
            String tier="free",expiresAt="";try(var q=c.prepareStatement("SELECT plan_code,expires_at FROM fanzui_user_entitlements WHERE user_id=? AND status='active' AND starts_at<=CURRENT_TIMESTAMP(6) AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(6)) ORDER BY starts_at DESC LIMIT 1")){q.setString(1,id);try(var r=q.executeQuery()){if(r.next()){tier=r.getString(1);Timestamp expiry=r.getTimestamp(2);expiresAt=expiry==null?"":expiry.toInstant().toString();}}}
            long points=0;try(var q=c.prepareStatement("SELECT COALESCE(SUM(points_delta),0) FROM fanzui_points_ledger WHERE user_id=?")){q.setString(1,id);try(var r=q.executeQuery()){if(r.next())points=r.getLong(1);}}
            boolean sync=false;try(var q=c.prepareStatement("SELECT granted FROM fanzui_user_consents WHERE user_id=? AND purpose='meal_cloud_sync'")){q.setString(1,id);try(var r=q.executeQuery()){sync=r.next()&&r.getBoolean(1);}}
            return mapper.writeValueAsString(Map.of("user",Map.of("id",id,"createdAt",created),"entitlement",Map.of("tier",tier,"expiresAt",expiresAt),"points",points,"cloudSyncEnabled",sync));
        }catch(Problem e){throw e;}catch(Exception e){throw unavailable(e);}
    }
    public void consent(String appId,String identityHash,boolean granted,String policyVersion){
        if(policyVersion==null||!policyVersion.matches("[A-Za-z0-9._-]{1,40}"))throw Problem.input("授权版本无效。");String id=requireUser(appId,identityHash);
        try(Connection c=open()){c.setAutoCommit(false);MealPhotos.lockUser(c,id);try(var q=c.prepareStatement("INSERT INTO fanzui_user_consents(user_id,purpose,policy_version,granted,updated_at) VALUES(?,'meal_cloud_sync',?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE policy_version=VALUES(policy_version),granted=VALUES(granted),updated_at=CURRENT_TIMESTAMP(6)")){q.setString(1,id);q.setString(2,policyVersion);q.setBoolean(3,granted);q.executeUpdate();}try(var q=c.prepareStatement("INSERT INTO fanzui_user_consent_events(consent_id,user_id,purpose,policy_version,granted,created_at) VALUES(?,?,'meal_cloud_sync',?,?,CURRENT_TIMESTAMP(6))")){q.setString(1,UUID.randomUUID().toString());q.setString(2,id);q.setString(3,policyVersion);q.setBoolean(4,granted);q.executeUpdate();}c.commit();}catch(SQLException e){throw unavailable(e);}
    }
    public boolean consented(String appId,String identityHash){String id=requireUser(appId,identityHash);try(Connection c=open();var q=c.prepareStatement("SELECT granted FROM fanzui_user_consents WHERE user_id=? AND purpose='meal_cloud_sync'")){q.setString(1,id);try(var r=q.executeQuery()){return r.next()&&r.getBoolean(1);}}catch(SQLException e){throw unavailable(e);}}
    public List<Map<String,Object>> meals(String appId,String identityHash){String id=requireConsent(appId,identityHash);try(Connection c=open();var q=c.prepareStatement("SELECT record_id,record_version,payload,updated_at FROM fanzui_user_meals WHERE user_id=? ORDER BY updated_at DESC LIMIT 1000")){q.setQueryTimeout(20);q.setString(1,id);try(var r=q.executeQuery()){var out=new ArrayList<Map<String,Object>>();while(r.next()){JsonNode payload=mapper.readTree(r.getString(3));out.add(Map.of("id",r.getString(1),"version",r.getLong(2),"updatedAt",r.getTimestamp(4).toInstant().toString(),"record",payload));}return out;}}catch(Problem e){throw e;}catch(Exception e){throw unavailable(e);}}
    public Map<String,Object> saveMeals(String appId,String identityHash,List<Map<String,Object>> records){String id=requireConsent(appId,identityHash);if(records==null||records.size()>20)throw Problem.input("每次最多同步20份饭案。");var saved=new ArrayList<Map<String,Object>>();var conflicts=new ArrayList<Map<String,Object>>();
        try(Connection c=open()){
            c.setAutoCommit(false);MealPhotos.lockUser(c,id);if(!MealPhotos.consent(c,id,"meal_cloud_sync"))throw new Problem(403,"CLOUD_SYNC_CONSENT_REQUIRED","请先同意饭案云端保存。");
            for(var row:records){String recordId=row.get("id") instanceof String s?s:"";long expected=row.get("version") instanceof Number n?n.longValue():0;Object raw=row.get("record");if(!recordId.matches("[A-Za-z0-9_-]{1,120}")||expected<0||expected>Long.MAX_VALUE-1||raw==null)throw Problem.input("饭案同步内容无效。");String json=mapper.writeValueAsString(raw);if(json.length()>160000)throw Problem.input("单份饭案过大，请减少图片或文字后重试。");JsonNode payload=mapper.readTree(json);if(!payload.isObject()||!recordId.equals(payload.path("id").asText()))throw Problem.input("饭案编号不一致。");
                Long actual=null;String oldJson=null;try(var q=c.prepareStatement("SELECT record_version,payload FROM fanzui_user_meals WHERE user_id=? AND record_id=? FOR UPDATE")){q.setString(1,id);q.setString(2,recordId);try(var r=q.executeQuery()){if(r.next()){actual=r.getLong(1);oldJson=r.getString(2);}}}
                if(payload.path("isDemo").asBoolean(false)||containsDevicePath(payload))throw Problem.input("演示饭案和本机图片路径不会同步到云端。");
                MealRecordVersion.validate(payload,oldJson==null?null:mapper.readTree(oldJson));
                if((actual==null&&expected==0)||(actual!=null&&actual==expected)){photos.attach(c,id,recordId,(com.fasterxml.jackson.databind.node.ObjectNode)payload);json=mapper.writeValueAsString(payload);}
                if(actual==null&&expected==0){try(var q=c.prepareStatement("INSERT INTO fanzui_user_meals(user_id,record_id,record_version,payload,updated_at) VALUES(?,?,1,?,CURRENT_TIMESTAMP(6))")){q.setString(1,id);q.setString(2,recordId);q.setString(3,json);q.executeUpdate();}saved.add(Map.of("id",recordId,"version",1));}
                else if(actual!=null&&actual==expected){long next=actual+1;try(var q=c.prepareStatement("UPDATE fanzui_user_meals SET record_version=?,payload=?,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND record_id=? AND record_version=?")){q.setLong(1,next);q.setString(2,json);q.setString(3,id);q.setString(4,recordId);q.setLong(5,actual);if(q.executeUpdate()!=1)throw new SQLException("version conflict");}saved.add(Map.of("id",recordId,"version",next));}
                else {JsonNode old=oldJson==null?mapper.createObjectNode():mapper.readTree(oldJson);conflicts.add(Map.of("id",recordId,"version",actual==null?0:actual,"record",old));}
            }
            c.commit();return Map.of("saved",saved,"conflicts",conflicts);
        }catch(Problem e){throw e;}catch(Exception e){throw unavailable(e);}
    }
    public void deleteMeal(String appId,String identityHash,String recordId){String id=requireUser(appId,identityHash);if(recordId==null||!recordId.matches("[A-Za-z0-9_-]{1,120}"))throw Problem.input("饭案编号无效。");try(Connection c=open()){c.setAutoCommit(false);MealPhotos.lockUser(c,id);photos.queueDelete(c,id,recordId);try(var q=c.prepareStatement("DELETE FROM fanzui_user_meals WHERE user_id=? AND record_id=?")){q.setString(1,id);q.setString(2,recordId);q.executeUpdate();}c.commit();}catch(SQLException e){throw unavailable(e);}}
    public String export(String appId,String identityHash){String id=requireUser(appId,identityHash);try(Connection c=open()){
        JsonNode account=mapper.readTree(account(appId,identityHash));var rows=mealsForExport(c,id);return mapper.writeValueAsString(Map.of("exportVersion","1","exportedAt",java.time.Instant.now().toString(),"account",account,"meals",rows,"health",healthForExport(c,id),"pointsLedger",pointsForExport(c,id),"orders",ordersForExport(c,id),"consents",consentsForExport(c,id)));
    }catch(Problem e){throw e;}catch(Exception e){throw unavailable(e);}}
    private Map<String,Object> healthForExport(Connection c,String id)throws Exception{try(var q=c.prepareStatement("SELECT health_version,profile_json,goals_json,updated_at FROM fanzui_user_health WHERE user_id=?")){q.setString(1,id);try(var r=q.executeQuery()){if(!r.next())return Map.of("version",0L,"profile",mapper.nullNode(),"goals",mapper.createArrayNode());return Map.of("version",r.getLong(1),"profile",r.getString(2)==null?mapper.nullNode():mapper.readTree(r.getString(2)),"goals",mapper.readTree(r.getString(3)),"updatedAt",r.getTimestamp(4).toInstant().toString());}}}
    private List<Map<String,Object>> mealsForExport(Connection c,String id)throws Exception{var rows=new ArrayList<Map<String,Object>>();try(var q=c.prepareStatement("SELECT record_id,record_version,payload,updated_at FROM fanzui_user_meals WHERE user_id=? ORDER BY updated_at")){q.setString(1,id);try(var r=q.executeQuery()){while(r.next())rows.add(Map.of("id",r.getString(1),"version",r.getLong(2),"updatedAt",r.getTimestamp(4).toInstant().toString(),"record",mapper.readTree(r.getString(3))));}}return rows;}
    private List<Map<String,Object>> pointsForExport(Connection c,String id)throws Exception{var rows=new ArrayList<Map<String,Object>>();try(var q=c.prepareStatement("SELECT points_delta,event_type,idempotency_key,reference_id,created_at FROM fanzui_points_ledger WHERE user_id=? ORDER BY created_at")){q.setString(1,id);try(var r=q.executeQuery()){while(r.next()){var row=new LinkedHashMap<String,Object>();row.put("delta",r.getInt(1));row.put("event",r.getString(2));row.put("idempotencyKey",r.getString(3));row.put("referenceId",r.getString(4));row.put("createdAt",r.getTimestamp(5).toInstant().toString());rows.add(row);}}}return rows;}
    private List<Map<String,Object>> ordersForExport(Connection c,String id)throws Exception{var rows=new ArrayList<Map<String,Object>>();try(var q=c.prepareStatement("SELECT order_id,plan_code,merchant_order_no,provider_trade_no,status,amount_minor,currency,created_at,updated_at FROM fanzui_membership_orders WHERE user_id=? ORDER BY created_at")){q.setString(1,id);try(var r=q.executeQuery()){while(r.next()){var row=new LinkedHashMap<String,Object>();row.put("id",r.getString(1));row.put("plan",r.getString(2));row.put("merchantOrderNo",r.getString(3));row.put("providerTradeNo",r.getString(4));row.put("status",r.getString(5));row.put("amountMinor",r.getLong(6));row.put("currency",r.getString(7));row.put("createdAt",r.getTimestamp(8).toInstant().toString());row.put("updatedAt",r.getTimestamp(9).toInstant().toString());rows.add(row);}}}return rows;}
    private List<Map<String,Object>> consentsForExport(Connection c,String id)throws Exception{var rows=new ArrayList<Map<String,Object>>();try(var q=c.prepareStatement("SELECT purpose,policy_version,granted,created_at FROM fanzui_user_consent_events WHERE user_id=? ORDER BY created_at")){q.setString(1,id);try(var r=q.executeQuery()){while(r.next())rows.add(Map.of("purpose",r.getString(1),"policyVersion",r.getString(2),"granted",r.getBoolean(3),"createdAt",r.getTimestamp(4).toInstant().toString()));}}return rows;}
    private static boolean containsDevicePath(JsonNode node){if(node.isObject()){var fields=node.fields();while(fields.hasNext()){var field=fields.next();String key=field.getKey().toLowerCase(Locale.ROOT);if(Set.of("path","localpath","tempfilepath","base64","owned").contains(key))return true;if(containsDevicePath(field.getValue()))return true;}}else if(node.isArray())for(JsonNode item:node)if(containsDevicePath(item))return true;return false;}
    public void deleteAllMeals(String appId,String identityHash){String id=requireUser(appId,identityHash);try(Connection c=open()){c.setAutoCommit(false);MealPhotos.lockUser(c,id);photos.queueDelete(c,id,null);try(var q=c.prepareStatement("DELETE FROM fanzui_user_meals WHERE user_id=?")){q.setString(1,id);q.executeUpdate();}c.commit();}catch(SQLException e){throw unavailable(e);}}
    public void deleteAccount(String appId,String identityHash){String id=requireUser(appId,identityHash);try(Connection c=open()){c.setAutoCommit(false);MealPhotos.lockUser(c,id);if(photos!=null)photos.queueDelete(c,id,null);for(String table:List.of("fanzui_user_meals","fanzui_user_health","fanzui_user_consent_events","fanzui_user_consents","fanzui_points_ledger","fanzui_user_entitlements","fanzui_membership_orders","fanzui_wechat_identities")){try(var q=c.prepareStatement("DELETE FROM "+table+" WHERE user_id=?")){q.setString(1,id);q.executeUpdate();}}try(var q=c.prepareStatement("DELETE FROM fanzui_users WHERE user_id=?")){q.setString(1,id);q.executeUpdate();}c.commit();}catch(SQLException e){throw unavailable(e);}}
    public Map<String,Object> photoConsent(String appId,String identity){String id=requireUser(appId,identity);try(Connection c=open()){return Map.of("granted",MealPhotos.consent(c,id,MealPhotos.PURPOSE),"policyVersion",MealPhotos.POLICY,"storageAvailable",true);}catch(SQLException e){throw unavailable(e);}}
    public void photoConsent(String appId,String identity,boolean granted){String id=requireUser(appId,identity);try(Connection c=open()){c.setAutoCommit(false);MealPhotos.lockUser(c,id);if(granted&&!MealPhotos.consent(c,id,"meal_cloud_sync"))throw new Problem(403,"CLOUD_SYNC_CONSENT_REQUIRED","请先开启饭案同步。");try(var q=c.prepareStatement("INSERT INTO fanzui_user_consents(user_id,purpose,policy_version,granted,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE policy_version=VALUES(policy_version),granted=VALUES(granted),updated_at=CURRENT_TIMESTAMP(6)")){q.setString(1,id);q.setString(2,MealPhotos.PURPOSE);q.setString(3,MealPhotos.POLICY);q.setBoolean(4,granted);q.executeUpdate();}try(var q=c.prepareStatement("INSERT INTO fanzui_user_consent_events(consent_id,user_id,purpose,policy_version,granted,created_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(6))")){q.setString(1,UUID.randomUUID().toString());q.setString(2,id);q.setString(3,MealPhotos.PURPOSE);q.setString(4,MealPhotos.POLICY);q.setBoolean(5,granted);q.executeUpdate();}c.commit();}catch(SQLException e){throw unavailable(e);}}
    public Map<String,Object> uploadPhoto(String appId,String identity,String record,String cloudFileId,String mime){
        String userId=requireUser(appId,identity);
        try(Connection c=open()){c.setAutoCommit(false);String fileId=photos.register(c,userId,record,cloudFileId,mime);c.commit();return Map.of("fileId",fileId,"mimeType",mime);}catch(SQLException e){throw unavailable(e);}
    }
    public Map<String,Object> photo(String appId,String identity,String fileId){String id=requireUser(appId,identity);try(Connection c=open()){c.setAutoCommit(false);var result=photos.read(c,id,fileId);c.commit();return result;}catch(SQLException e){throw unavailable(e);}}
    public Map<String,Object> profile(String appId,String identity){
        String id=requireUser(appId,identity);
        try(Connection c=open()){
            String nickname=null,avatarFileId=null,avatarMime=null;
            try(var q=c.prepareStatement("SELECT nickname,avatar_file_id,avatar_mime_type FROM fanzui_users WHERE user_id=?")){q.setString(1,id);try(var r=q.executeQuery()){if(r.next()){nickname=r.getString(1);avatarFileId=r.getString(2);avatarMime=r.getString(3);}}}
            return Map.of("nickname",nickname==null?"":nickname,"avatarFileId",avatarFileId==null?"":avatarFileId,"avatarMimeType",avatarMime==null?"":avatarMime);
        }catch(Problem e){throw e;}catch(Exception e){throw unavailable(e);}
    }
    public Map<String,Object> updateProfile(String appId,String identity,String nickname,String avatarFileId,String avatarMime){
        String id=requireUser(appId,identity);
        String name=nickname==null?"":nickname.trim();
        if(name.length()>64)throw Problem.input("昵称最多64个字符。");
        String fileRef=avatarFileId==null||avatarFileId.isBlank()?null:MealPhotos.validCloudFileId(avatarFileId);
        if(fileRef!=null&&!MealPhotos.validMime(avatarMime))throw Problem.input("头像格式无效，请选择 JPEG、PNG 或 WebP。");
        try(Connection c=open()){
            if(fileRef!=null){
                try(var q=c.prepareStatement("UPDATE fanzui_users SET nickname=?,avatar_file_id=?,avatar_mime_type=?,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=?")){q.setString(1,name.isBlank()?null:name);q.setString(2,fileRef);q.setString(3,avatarMime);q.setString(4,id);q.executeUpdate();}
            }else{
                try(var q=c.prepareStatement("UPDATE fanzui_users SET nickname=?,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=?")){q.setString(1,name.isBlank()?null:name);q.setString(2,id);q.executeUpdate();}
            }
        }catch(SQLException e){throw unavailable(e);}
        return profile(appId,identity);
    }
    /** Confirmed profile and goal history; daily markers stay on the device that recorded them. */
    public Map<String,Object> health(String appId,String identity){
        String id=requireConsent(appId,identity);
        try(Connection c=open();var q=c.prepareStatement("SELECT health_version,profile_json,goals_json FROM fanzui_user_health WHERE user_id=?")){
            q.setString(1,id);
            try(var r=q.executeQuery()){
                if(!r.next())return Map.of("version",0L,"profile",mapper.nullNode(),"goals",mapper.createArrayNode());
                JsonNode profile=r.getString(2)==null?mapper.nullNode():mapper.readTree(r.getString(2));
                return Map.of("version",r.getLong(1),"profile",profile,"goals",mapper.readTree(r.getString(3)));
            }
        }catch(Problem e){throw e;}catch(Exception e){throw unavailable(e);}
    }
    /** expected is the version the device last saw; a mismatch returns the stored version instead of overwriting it. */
    public Map<String,Object> saveHealth(String appId,String identity,Long expected,JsonNode profile,JsonNode goals){
        String id=requireConsent(appId,identity);
        long base=expected==null?0:expected;
        if(base<0)throw Problem.input("健康档案版本无效。");
        JsonNode cleanProfile=UserHealth.profile(mapper,profile);
        JsonNode cleanGoals=UserHealth.goals(mapper,goals);
        try{
            String profileJson=cleanProfile==null?null:mapper.writeValueAsString(cleanProfile);
            String goalsJson=mapper.writeValueAsString(cleanGoals);
            UserHealth.checkSize(profileJson,goalsJson);
            try(Connection c=open()){
                c.setAutoCommit(false);MealPhotos.lockUser(c,id);
                Long actual=null;
                try(var q=c.prepareStatement("SELECT health_version FROM fanzui_user_health WHERE user_id=? FOR UPDATE")){q.setString(1,id);try(var r=q.executeQuery()){if(r.next())actual=r.getLong(1);}}
                long current=actual==null?0:actual;
                if(current!=base){c.rollback();return Map.of("version",current,"conflict",true);}
                long next=current+1;
                if(actual==null){
                    try(var q=c.prepareStatement("INSERT INTO fanzui_user_health(user_id,health_version,profile_json,goals_json,updated_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP(6))")){q.setString(1,id);q.setLong(2,next);q.setString(3,profileJson);q.setString(4,goalsJson);q.executeUpdate();}
                }else{
                    try(var q=c.prepareStatement("UPDATE fanzui_user_health SET health_version=?,profile_json=?,goals_json=?,updated_at=CURRENT_TIMESTAMP(6) WHERE user_id=? AND health_version=?")){q.setLong(1,next);q.setString(2,profileJson);q.setString(3,goalsJson);q.setString(4,id);q.setLong(5,current);if(q.executeUpdate()!=1)throw new SQLException("version conflict");}
                }
                c.commit();return Map.of("version",next,"conflict",false);
            }
        }catch(Problem e){throw e;}catch(Exception e){throw unavailable(e);}
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000,initialDelay=30000)
    public void cleanupPhotos(){try(Connection c=open()){c.setAutoCommit(false);photos.cleanup(c);}catch(Exception e){org.slf4j.LoggerFactory.getLogger(UserDataStore.class).warn("Photo cleanup pending; will retry. type={}",e.getClass().getSimpleName());}}
    private String requireConsent(String appId,String identityHash){String id=requireUser(appId,identityHash);if(!consented(appId,identityHash))throw new Problem(403,"CLOUD_SYNC_CONSENT_REQUIRED","请先单独同意饭案云端保存。");return id;}
    private static Problem unauthorized(){return new Problem(401,"UNAUTHORIZED","账号不可用，请重新登录。");}
    private static Problem unavailable(Throwable cause){
        // 503 原先吞掉了一切原因，缺表、断连、字段不符在界面上一模一样。记下类型和 SQL 错误码，
        // 下次看日志就能定位，不用再靠猜。只记错误码，不记 SQL 文本，避免带出用户数据。
        if(cause instanceof SQLException sql)LOG.warn("Account data store unavailable. type={} sqlState={} vendorCode={}",sql.getClass().getSimpleName(),sql.getSQLState(),sql.getErrorCode());
        else LOG.warn("Account data store unavailable. type={}",cause==null?"unknown":cause.getClass().getSimpleName());
        return new Problem(503,"USER_STORE_UNAVAILABLE","账号数据暂时无法读取或保存，请稍后重试；本机记录仍保留。");
    }
}
