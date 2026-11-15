package com.fanzuixianchang.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.net.URI;
import java.sql.*;
import java.time.*;
import java.util.*;

/** User-facing account data exposed through the separately authenticated operations console. */
public final class AdminUserStore {
    private final ObjectMapper mapper;
    private final String url,user,password;
    private volatile boolean schemaReady;

    public AdminUserStore(ObjectMapper mapper,String url,String user,String password){this(mapper,url,user,password,true);}
    AdminUserStore(ObjectMapper mapper,String url,String user,String password,boolean validate){
        this.mapper=mapper;this.url=url;this.user=user;this.password=password;
        if(validate)validateUrl(url);
    }

    private static void validateUrl(String value){
        try{if(value==null||!value.startsWith("jdbc:mysql://"))throw new IllegalArgumentException();var uri=URI.create(value.substring(5));if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPath()==null||!uri.getPath().matches("/[A-Za-z0-9_]+"))throw new IllegalArgumentException();}
        catch(RuntimeException e){throw new IllegalArgumentException("MySQL URL must name a database without embedded credentials");}
    }

    private Connection open()throws SQLException{
        var props=new Properties();props.setProperty("user",user);props.setProperty("password",password);
        props.setProperty("connectTimeout","10000");props.setProperty("socketTimeout","20000");
        props.setProperty("allowLoadLocalInfile","false");props.setProperty("allowUrlInLocalInfile","false");
        return DriverManager.getConnection(url,props);
    }

    private synchronized void ensureSchema(){
        if(schemaReady)return;
        try(Connection c=open();var statement=c.createStatement()){
            statement.execute("CREATE TABLE IF NOT EXISTS fanzui_ops_admin_events (event_id CHAR(36) NOT NULL PRIMARY KEY,actor VARCHAR(64) NOT NULL,action VARCHAR(40) NOT NULL,target_type VARCHAR(24) NOT NULL,target_id VARCHAR(120) NOT NULL,reason VARCHAR(300) NULL,detail_json LONGTEXT NOT NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6))");
            statement.execute("CREATE TABLE IF NOT EXISTS fanzui_ops_settings (setting_key VARCHAR(64) NOT NULL PRIMARY KEY,value_json LONGTEXT NOT NULL,updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6))");
            schemaReady=true;
        }catch(SQLException e){throw dbFailure(e);}
    }

    public Map<String,Object> dashboard(){
        ensureSchema();
        try(Connection c=open()){
            var data=new LinkedHashMap<String,Object>();
            data.put("users",scalarLong(c,"SELECT COUNT(*) FROM fanzui_users WHERE status='active'"));
            data.put("newToday",scalarLong(c,"SELECT COUNT(*) FROM fanzui_users WHERE status='active' AND created_at>=CURRENT_DATE()"));
            data.put("syncedUsers",scalarLong(c,"SELECT COUNT(*) FROM fanzui_user_consents WHERE purpose='meal_cloud_sync' AND granted=TRUE"));
            data.put("cloudMeals",scalarLong(c,"SELECT COUNT(*) FROM fanzui_user_meals"));
            data.put("entitledUsers",scalarLong(c,"SELECT COUNT(DISTINCT user_id) FROM fanzui_user_entitlements WHERE status='active' AND starts_at<=CURRENT_TIMESTAMP(6) AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP(6))"));
            data.put("pointsLast7Days",scalarLong(c,"SELECT COALESCE(SUM(points_delta),0) FROM fanzui_points_ledger WHERE created_at>=TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP(6))"));
            data.put("tiers",tierDistribution(c));
            return data;
        }catch(SQLException e){throw dbFailure(e);}
    }

    public Map<String,Object> users(UserQuery input){
        ensureSchema();
        var q=input==null?new UserQuery(null,null,null,null,1,20):input;
        var query=trim(q.query(),80);
        var status=trim(q.status(),16);
        var tier=trim(q.tier(),40);
        if(status!=null&&!Set.of("active","deleting").contains(status))throw Problem.input("用户状态无效。");
        if(tier!=null&&!tier.matches("[a-z0-9_-]{1,40}"))throw Problem.input("等级筛选无效。");
        int page=Math.max(1,Math.min(q.page()==null?1:q.page(),100000));
        int pageSize=Math.max(1,Math.min(q.pageSize()==null?20:q.pageSize(),50));
        var where=new ArrayList<String>();var args=new ArrayList<Object>();
        if(query!=null){where.add("(u.user_id LIKE ? OR u.nickname LIKE ?)");args.add("%"+query+"%");args.add("%"+query+"%");}
        if(status!=null){where.add("u.status=?");args.add(status);}
        if(q.sync()!=null){where.add("COALESCE((SELECT c.granted FROM fanzui_user_consents c WHERE c.user_id=u.user_id AND c.purpose='meal_cloud_sync'),FALSE)=?");args.add(q.sync());}
        String tierExpr=currentTierSql();
        if(tier!=null){where.add("COALESCE("+tierExpr+",'free')=?");args.add(tier);}
        String suffix=where.isEmpty()?"":(" WHERE "+String.join(" AND ",where));
        try(Connection c=open()){
            long total;
            try(var statement=c.prepareStatement("SELECT COUNT(*) FROM fanzui_users u"+suffix)){
                bind(statement,args,1);try(var rs=statement.executeQuery()){rs.next();total=rs.getLong(1);}
            }
            var rows=new ArrayList<Map<String,Object>>();
            String sql=userSelectSql(tierExpr)+suffix+" ORDER BY u.created_at DESC LIMIT ? OFFSET ?";
            try(var statement=c.prepareStatement(sql)){
                int index=bind(statement,args,1);
                statement.setInt(index++,pageSize);statement.setInt(index,(page-1)*pageSize);
                try(var rs=statement.executeQuery()){while(rs.next())rows.add(userRow(rs));}
            }
            return Map.of("items",rows,"page",page,"pageSize",pageSize,"total",total,"hasMore",page*pageSize<total);
        }catch(SQLException e){throw dbFailure(e);}
    }

    public Map<String,Object> detail(String userId){
        ensureSchema();
        requireId(userId);
        try(Connection c=open()){
            Map<String,Object> user;
            try(var q=c.prepareStatement(userSelectSql(currentTierSql())+" WHERE u.user_id=?")){
                q.setString(1,userId);try(var rs=q.executeQuery()){if(!rs.next())throw notFound();user=userRow(rs);}
            }
            var points=new ArrayList<Map<String,Object>>();
            try(var q=c.prepareStatement("SELECT ledger_id,points_delta,event_type,idempotency_key,reference_id,created_at FROM fanzui_points_ledger WHERE user_id=? ORDER BY created_at DESC LIMIT 20")){
                q.setString(1,userId);try(var rs=q.executeQuery()){while(rs.next()){var row=new LinkedHashMap<String,Object>();row.put("id",rs.getString(1));row.put("delta",rs.getInt(2));row.put("eventType",rs.getString(3));row.put("idempotencyKey",rs.getString(4));row.put("reason",rs.getString(5));row.put("createdAt",stamp(rs.getTimestamp(6)));points.add(row);}}
            }
            var entitlements=new ArrayList<Map<String,Object>>();
            try(var q=c.prepareStatement("SELECT e.entitlement_id,e.plan_code,p.display_name,e.status,e.starts_at,e.expires_at,e.created_at FROM fanzui_user_entitlements e LEFT JOIN fanzui_membership_plans p ON p.plan_code=e.plan_code WHERE e.user_id=? ORDER BY e.starts_at DESC LIMIT 30")){
                q.setString(1,userId);try(var rs=q.executeQuery()){while(rs.next()){var row=new LinkedHashMap<String,Object>();row.put("id",rs.getString(1));row.put("planCode",rs.getString(2));row.put("planName",rs.getString(3)==null?rs.getString(2):rs.getString(3));row.put("status",rs.getString(4));row.put("startsAt",stamp(rs.getTimestamp(5)));row.put("expiresAt",stamp(rs.getTimestamp(6)));row.put("createdAt",stamp(rs.getTimestamp(7)));entitlements.add(row);}}
            }
            var consents=new ArrayList<Map<String,Object>>();
            try(var q=c.prepareStatement("SELECT purpose,granted,policy_version,updated_at FROM fanzui_user_consents WHERE user_id=? ORDER BY purpose")){
                q.setString(1,userId);try(var rs=q.executeQuery()){while(rs.next())consents.add(Map.of("purpose",rs.getString(1),"granted",rs.getBoolean(2),"policyVersion",rs.getString(3),"updatedAt",stamp(rs.getTimestamp(4))));}
            }
            return Map.of("user",user,"pointsLedger",points,"entitlements",entitlements,"consents",consents);
        }catch(SQLException e){throw dbFailure(e);}
    }

    public Map<String,Object> plans(){
        ensureSchema();
        try(Connection c=open()){
            var rows=new ArrayList<Map<String,Object>>();
            try(var q=c.prepareStatement("SELECT plan_code,display_name,active,entitlement_json,created_at FROM fanzui_membership_plans ORDER BY active DESC,display_name")){
                try(var rs=q.executeQuery()){while(rs.next()){var row=new LinkedHashMap<String,Object>();row.put("code",rs.getString(1));row.put("name",rs.getString(2));row.put("active",rs.getBoolean(3));String json=rs.getString(4);try{row.put("entitlement",mapper.readTree(json));}catch(Exception e){row.put("entitlement",json);}row.put("createdAt",stamp(rs.getTimestamp(5)));rows.add(row);}}
            }
            return Map.of("items",rows);
        }catch(SQLException e){throw dbFailure(e);}
    }

    public Map<String,Object> savePlan(String code,PlanInput input,String actor){
        ensureSchema();
        if(input==null)throw Problem.input("请填写等级配置。");
        if(code==null||!code.matches("[a-z0-9_-]{2,40}"))throw Problem.input("等级标识需为2—40位小写字母、数字、下划线或短横线。");
        var name=requireText(input.displayName(),80,"等级名称");
        String entitlement=trim(input.entitlementJson(),2000);
        if(entitlement==null)entitlement="{\"description\":\"\"}";
        try{ mapper.readTree(entitlement); }catch(Exception e){throw Problem.input("权益配置必须是有效 JSON。");}
        try(Connection c=open()){
            c.setAutoCommit(false);
            try(var q=c.prepareStatement("INSERT INTO fanzui_membership_plans(plan_code,display_name,active,entitlement_json) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),active=VALUES(active),entitlement_json=VALUES(entitlement_json)")){
                q.setString(1,code);q.setString(2,name);q.setBoolean(3,input.active());q.setString(4,entitlement);q.executeUpdate();
            }
            audit(c,actor,"save_plan","plan",code,null,Map.of("name",name,"active",input.active(),"entitlement",entitlement));
            c.commit();return Map.of("code",code,"name",name,"active",input.active(),"entitlement",mapper.readTree(entitlement));
        }catch(Exception e){throw dbFailure(e);}
    }

    public Map<String,Object> adjustPoints(String userId,PointInput input,String actor){
        ensureSchema();
        requireId(userId);
        int delta=input==null||input.delta()==null?0:input.delta();
        if(delta==0||Math.abs(delta)>100000)throw Problem.input("积分变动需为非零值，绝对值不超过100000。");
        var reason=requireText(input.reason(),120,"调整原因");
        var key=requireText(input.idempotencyKey(),120,"幂等键");
        if(key.length()<8)throw Problem.input("幂等键至少8位。");
        try(Connection c=open()){
            var existing=pointByKey(c,userId,key);
            if(existing!=null)return existing;
            c.setAutoCommit(false);
            requireUser(c,userId);
            String id=UUID.randomUUID().toString();
            try(var q=c.prepareStatement("INSERT INTO fanzui_points_ledger(ledger_id,user_id,points_delta,event_type,idempotency_key,reference_id) VALUES(?,?,?,'manual_adjustment',?,?)")){
                q.setString(1,id);q.setString(2,userId);q.setInt(3,delta);q.setString(4,key);q.setString(5,reason);q.executeUpdate();
            }catch(SQLException e){
                if(e.getErrorCode()==1062){c.rollback();var saved=pointByKey(c,userId,key);if(saved!=null)return saved;}
                throw e;
            }
            audit(c,actor,"adjust_points","user",userId,reason,Map.of("delta",delta,"idempotencyKey",key));
            c.commit();return Map.of("id",id,"delta",delta,"reason",reason,"createdAt",Instant.now().toString());
        }catch(Problem e){throw e;}catch(Exception e){throw dbFailure(e);}
    }

    public Map<String,Object> assignEntitlement(String userId,EntitlementInput input,String actor){
        ensureSchema();
        requireId(userId);
        if(input==null)throw Problem.input("请填写等级权益。");
        var code=requireText(input.planCode(),40,"等级");
        Timestamp starts=parseTime(input.startsAt(),"开始时间",false);
        Timestamp expires=parseTime(input.expiresAt(),"到期时间",true);
        if(expires!=null&&!expires.after(starts))throw Problem.input("到期时间必须晚于开始时间。");
        var reason=trim(input.reason(),300);
        try(Connection c=open()){
            c.setAutoCommit(false);requireUser(c,userId);
            try(var q=c.prepareStatement("SELECT active FROM fanzui_membership_plans WHERE plan_code=?")){q.setString(1,code);try(var rs=q.executeQuery()){if(!rs.next()||!rs.getBoolean(1))throw Problem.input("请选择已启用的等级。");}}
            String id=UUID.randomUUID().toString();
            try(var q=c.prepareStatement("INSERT INTO fanzui_user_entitlements(entitlement_id,user_id,plan_code,status,starts_at,expires_at) VALUES(?, ?,?,'active',?,?)")){
                q.setString(1,id);q.setString(2,userId);q.setString(3,code);q.setTimestamp(4,starts);if(expires==null)q.setNull(5,Types.TIMESTAMP);else q.setTimestamp(5,expires);q.executeUpdate();
            }
            audit(c,actor,"assign_entitlement","user",userId,reason,Map.of("entitlementId",id,"planCode",code,"startsAt",starts.toInstant().toString(),"expiresAt",expires==null?"":expires.toInstant().toString()));
            c.commit();return Map.of("id",id,"planCode",code,"status","active","startsAt",starts.toInstant().toString(),"expiresAt",expires==null?"":expires.toInstant().toString());
        }catch(Problem e){throw e;}catch(Exception e){throw dbFailure(e);}
    }

    public Map<String,Object> revokeEntitlement(String userId,String entitlementId,String reason,String actor){
        ensureSchema();
        requireId(userId);requireId(entitlementId);
        var note=requireText(reason,300,"撤销原因");
        try(Connection c=open()){
            c.setAutoCommit(false);
            try(var q=c.prepareStatement("UPDATE fanzui_user_entitlements SET status='revoked' WHERE entitlement_id=? AND user_id=? AND status='active'")){
                q.setString(1,entitlementId);q.setString(2,userId);if(q.executeUpdate()!=1)throw Problem.input("未找到可撤销的等级权益。");
            }
            audit(c,actor,"revoke_entitlement","user",userId,note,Map.of("entitlementId",entitlementId));
            c.commit();return Map.of("id",entitlementId,"status","revoked");
        }catch(Problem e){throw e;}catch(Exception e){throw dbFailure(e);}
    }

    public Map<String,Object> settings(){
        ensureSchema();
        try(Connection c=open()){
            String announcement="";
            try(var q=c.prepareStatement("SELECT value_json FROM fanzui_ops_settings WHERE setting_key='user_announcement'")){
                try(var rs=q.executeQuery()){if(rs.next()){try{announcement=mapper.readTree(rs.getString(1)).path("text").asText("");}catch(Exception ignored){announcement="";}}}
            }
            return Map.of("userAnnouncement",announcement);
        }catch(SQLException e){throw dbFailure(e);}
    }

    public Map<String,Object> saveSettings(SettingsInput input,String actor){
        ensureSchema();
        var announcement=trim(input==null?null:input.userAnnouncement(),2000);
        if(announcement==null)announcement="";
        try(Connection c=open()){
            c.setAutoCommit(false);
            String json=mapper.writeValueAsString(Map.of("text",announcement));
            try(var q=c.prepareStatement("INSERT INTO fanzui_ops_settings(setting_key,value_json) VALUES('user_announcement',?) ON DUPLICATE KEY UPDATE value_json=VALUES(value_json),updated_at=CURRENT_TIMESTAMP(6)")){q.setString(1,json);q.executeUpdate();}
            audit(c,actor,"save_settings","settings","user_announcement",null,Map.of("userAnnouncementLength",announcement.length()));
            c.commit();return Map.of("userAnnouncement",announcement);
        }catch(Exception e){throw dbFailure(e);}
    }

    public Map<String,Object> audit(){
        ensureSchema();
        try(Connection c=open()){
            var rows=new ArrayList<Map<String,Object>>();
            try(var q=c.prepareStatement("SELECT event_id,actor,action,target_type,target_id,reason,detail_json,created_at FROM fanzui_ops_admin_events ORDER BY created_at DESC LIMIT 100")){
                try(var rs=q.executeQuery()){while(rs.next()){var row=new LinkedHashMap<String,Object>();row.put("id",rs.getString(1));row.put("actor",rs.getString(2));row.put("action",rs.getString(3));row.put("targetType",rs.getString(4));row.put("targetId",rs.getString(5));row.put("reason",rs.getString(6));row.put("detail",mapper.readTree(rs.getString(7)));row.put("createdAt",stamp(rs.getTimestamp(8)));rows.add(row);}}
            }
            return Map.of("items",rows);
        }catch(Exception e){throw dbFailure(e);}
    }

    private List<Map<String,Object>> tierDistribution(Connection c)throws SQLException{
        var rows=new ArrayList<Map<String,Object>>();
        String sql="SELECT COALESCE(t.current_plan,'free') tier,COUNT(*) total FROM (SELECT u.user_id,"+currentTierSql()+" current_plan FROM fanzui_users u WHERE u.status='active') t GROUP BY COALESCE(t.current_plan,'free') ORDER BY total DESC";
        try(var q=c.prepareStatement(sql);var rs=q.executeQuery()){while(rs.next())rows.add(Map.of("tier",rs.getString(1),"count",rs.getLong(2)));}
        return rows;
    }

    private Map<String,Object> userRow(ResultSet rs)throws SQLException{
        var row=new LinkedHashMap<String,Object>();
        row.put("id",rs.getString("user_id"));row.put("nickname",rs.getString("nickname")==null?"未设置昵称":rs.getString("nickname"));
        row.put("status",rs.getString("status"));row.put("createdAt",stamp(rs.getTimestamp("created_at")));row.put("updatedAt",stamp(rs.getTimestamp("updated_at")));
        row.put("tier",rs.getString("tier")==null?"free":rs.getString("tier"));row.put("points",rs.getLong("points"));
        row.put("mealCount",rs.getLong("meal_count"));row.put("lastMealAt",stamp(rs.getTimestamp("last_meal_at")));
        row.put("cloudSyncEnabled",rs.getBoolean("cloud_sync"));row.put("healthSynced",rs.getBoolean("health_synced"));
        row.put("cloudPhotoCount",rs.getLong("cloud_photo_count"));
        return row;
    }

    private static String currentTierSql(){return "(SELECT e.plan_code FROM fanzui_user_entitlements e WHERE e.user_id=u.user_id AND e.status='active' AND e.starts_at<=CURRENT_TIMESTAMP(6) AND (e.expires_at IS NULL OR e.expires_at>CURRENT_TIMESTAMP(6)) ORDER BY e.starts_at DESC LIMIT 1)";}
    private static String userSelectSql(String tierExpr){return "SELECT u.user_id,u.nickname,u.status,u.created_at,u.updated_at,"+tierExpr+" tier,"+
        "(SELECT COALESCE(SUM(p.points_delta),0) FROM fanzui_points_ledger p WHERE p.user_id=u.user_id) points,"+
        "(SELECT COUNT(*) FROM fanzui_user_meals m WHERE m.user_id=u.user_id) meal_count,"+
        "(SELECT MAX(m.updated_at) FROM fanzui_user_meals m WHERE m.user_id=u.user_id) last_meal_at,"+
        "COALESCE((SELECT c.granted FROM fanzui_user_consents c WHERE c.user_id=u.user_id AND c.purpose='meal_cloud_sync'),FALSE) cloud_sync,"+
        "EXISTS(SELECT 1 FROM fanzui_user_health h WHERE h.user_id=u.user_id) health_synced,"+
        "(SELECT COUNT(*) FROM fanzui_meal_photos ph WHERE ph.user_id=u.user_id AND ph.state IN ('uploaded','linked')) cloud_photo_count "+
        "FROM fanzui_users u";}
    private static int bind(PreparedStatement q,List<Object> values,int start)throws SQLException{int i=start;for(Object value:values){if(value instanceof Boolean b)q.setBoolean(i++,b);else q.setObject(i++,value);}return i;}
    private static long scalarLong(Connection c,String sql)throws SQLException{try(var q=c.prepareStatement(sql);var rs=q.executeQuery()){rs.next();return rs.getLong(1);}}
    private static String stamp(Timestamp value){return value==null?"":value.toInstant().toString();}
    private static String trim(String value,int max){if(value==null)return null;var text=value.trim();if(text.isEmpty())return null;if(text.length()>max)throw Problem.input("文字内容过长。");return text;}
    private static String requireText(String value,int max,String label){var text=trim(value,max);if(text==null)throw Problem.input("请填写"+label+"。");return text;}
    private static void requireId(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,120}"))throw Problem.input("编号无效。");}
    private static void requireUser(Connection c,String id)throws SQLException{try(var q=c.prepareStatement("SELECT status FROM fanzui_users WHERE user_id=? FOR UPDATE")){q.setString(1,id);try(var rs=q.executeQuery()){if(!rs.next()||!"active".equals(rs.getString(1)))throw Problem.input("用户不存在或不可用。");}}}
    private static Map<String,Object> pointByKey(Connection c,String user,String key)throws SQLException{try(var q=c.prepareStatement("SELECT ledger_id,points_delta,reference_id,created_at FROM fanzui_points_ledger WHERE user_id=? AND idempotency_key=?")){q.setString(1,user);q.setString(2,key);try(var rs=q.executeQuery()){if(!rs.next())return null;return Map.of("id",rs.getString(1),"delta",rs.getInt(2),"reason",rs.getString(3)==null?"":rs.getString(3),"createdAt",stamp(rs.getTimestamp(4)));}}}
    private static Timestamp parseTime(String value,String label,boolean optional){if(value==null||value.isBlank()){if(optional)return null;throw Problem.input("请填写"+label+"。");}try{return Timestamp.from(Instant.parse(value));}catch(Exception ignored){}try{return Timestamp.from(LocalDateTime.parse(value.replace(' ','T')).atZone(ZoneId.of("Asia/Shanghai")).toInstant());}catch(Exception e){throw Problem.input(label+"格式无效。");}}
    private void audit(Connection c,String actor,String action,String targetType,String targetId,String reason,Object detail){
        try(var q=c.prepareStatement("INSERT INTO fanzui_ops_admin_events(event_id,actor,action,target_type,target_id,reason,detail_json) VALUES(?,?,?,?,?,?,?)")){
            q.setString(1,UUID.randomUUID().toString());q.setString(2,actor==null||actor.isBlank()?"unknown-admin":actor);q.setString(3,action);q.setString(4,targetType);q.setString(5,targetId);q.setString(6,reason);q.setString(7,mapper.writeValueAsString(detail));q.executeUpdate();
        }catch(Exception e){throw dbFailure(e);}
    }
    private static Problem unavailable(){return new Problem(503,"USER_STORE_UNAVAILABLE","用户运营数据暂时无法读取或保存，请稍后重试。");}
    private static Problem notFound(){return new Problem(404,"USER_NOT_FOUND","用户不存在。");}
    private static RuntimeException dbFailure(Exception e){if(e instanceof Problem p)return p;var problem=unavailable();problem.initCause(e);return problem;}
    private void audit(Connection c,String actor,String action,String targetType,String targetId,String reason,Map<String,Object> detail)throws SQLException{try{audit(c,actor,action,targetType,targetId,reason,(Object)detail);}catch(RuntimeException e){if(e.getCause()instanceof SQLException se)throw se;throw e;}}

    public record UserQuery(String query,String status,Boolean sync,String tier,Integer page,Integer pageSize){}
    public record PointInput(Integer delta,String reason,String idempotencyKey){}
    public record PlanInput(String displayName,boolean active,String entitlementJson){}
    public record EntitlementInput(String planCode,String startsAt,String expiresAt,String reason){}
    public record SettingsInput(String userAnnouncement){}
}
