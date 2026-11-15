package com.fanzuixianchang.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminUserStoreTest {
    private Connection connection;
    private AdminUserStore store;

    @BeforeEach void setup()throws Exception{
        String name="admin-user-"+UUID.randomUUID();
        connection=DriverManager.getConnection("jdbc:h2:mem:"+name+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","");
        try(var q=connection.createStatement()){
            q.execute("CREATE TABLE fanzui_users(user_id VARCHAR(36) PRIMARY KEY,status VARCHAR(16),nickname VARCHAR(64),avatar_file_id VARCHAR(255),avatar_mime_type VARCHAR(32),created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))");
            q.execute("CREATE TABLE fanzui_user_consents(user_id VARCHAR(36),purpose VARCHAR(40),policy_version VARCHAR(40),granted BOOLEAN,updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))");
            q.execute("CREATE TABLE fanzui_user_meals(user_id VARCHAR(36),record_id VARCHAR(120),record_version BIGINT,payload LONGTEXT,updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))");
            q.execute("CREATE TABLE fanzui_user_health(user_id VARCHAR(36),health_version BIGINT,profile_json LONGTEXT,goals_json LONGTEXT,updated_at TIMESTAMP(6))");
            q.execute("CREATE TABLE fanzui_meal_photos(file_id VARCHAR(36),user_id VARCHAR(36),record_id VARCHAR(120),cloud_file_id VARCHAR(255),mime_type VARCHAR(32),state VARCHAR(16),created_at TIMESTAMP(6))");
            q.execute("CREATE TABLE fanzui_membership_plans(plan_code VARCHAR(40) PRIMARY KEY,display_name VARCHAR(80),active BOOLEAN,entitlement_json LONGTEXT,created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))");
            q.execute("CREATE TABLE fanzui_user_entitlements(entitlement_id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36),plan_code VARCHAR(40),status VARCHAR(16),source_order_id VARCHAR(36),starts_at TIMESTAMP(6),expires_at TIMESTAMP(6),created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))");
            q.execute("CREATE TABLE fanzui_points_ledger(ledger_id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36),points_delta INT,event_type VARCHAR(40),idempotency_key VARCHAR(120),reference_id VARCHAR(120),created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),UNIQUE(user_id,idempotency_key))");
            q.execute("CREATE TABLE fanzui_ops_admin_events(event_id VARCHAR(36) PRIMARY KEY,actor VARCHAR(64),action VARCHAR(40),target_type VARCHAR(24),target_id VARCHAR(120),reason VARCHAR(300),detail_json LONGTEXT,created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))");
            q.execute("CREATE TABLE fanzui_ops_settings(setting_key VARCHAR(64) PRIMARY KEY,value_json LONGTEXT,updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))");
            q.execute("INSERT INTO fanzui_users(user_id,status,nickname) VALUES('u1','active','小饭')");
            q.execute("INSERT INTO fanzui_user_consents(user_id,purpose,policy_version,granted) VALUES('u1','meal_cloud_sync','v1',TRUE)");
            q.execute("INSERT INTO fanzui_user_meals(user_id,record_id,record_version,payload) VALUES('u1','m1',1,'{}')");
            q.execute("INSERT INTO fanzui_user_health(user_id,health_version,profile_json,goals_json,updated_at) VALUES('u1',1,'{\"secret\":\"do-not-expose\"}','[]',CURRENT_TIMESTAMP)");
        }
        store=new AdminUserStore(new ObjectMapper(),"jdbc:h2:mem:"+name+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","",false);
    }

    @AfterEach void close()throws Exception{connection.close();}

    @Test void userListAndDetailExposeOperationalSummaryWithoutPrivatePayloads(){
        assertEquals(1L,store.dashboard().get("users"));
        var list=store.users(new AdminUserStore.UserQuery("小",null,true,null,1,20));
        assertEquals(1L,list.get("total"));
        @SuppressWarnings("unchecked") var item=((java.util.List<java.util.Map<String,Object>>)list.get("items")).get(0);
        assertEquals("u1",item.get("id"));assertEquals("小饭",item.get("nickname"));assertEquals(1L,item.get("mealCount"));
        var detail=store.detail("u1");
        String json=detail.toString();
        assertFalse(json.contains("do-not-expose"));assertFalse(json.contains("payload"));assertFalse(json.contains("openid"));
    }

    @Test void pointsAdjustmentIsIdempotentAndAudited(){
        var first=store.adjustPoints("u1",new AdminUserStore.PointInput(30,"活动补偿","manual-key-0001"),"temporary-admin");
        var repeated=store.adjustPoints("u1",new AdminUserStore.PointInput(30,"活动补偿","manual-key-0001"),"temporary-admin");
        assertEquals(first.get("id"),repeated.get("id"));
        var detail=store.detail("u1");
        assertEquals(30L,((java.util.Map<?,?>)detail.get("user")).get("points"));
        assertEquals(1,((java.util.List<?>)detail.get("pointsLedger")).size());
        assertEquals(1,((java.util.List<?>)store.audit().get("items")).size());
    }

    @Test void plansEntitlementsAndSettingsRemainTraceable(){
        store.savePlan("pro",new AdminUserStore.PlanInput("专业搭档",true,"{\"mealHistory\":true}"),"temporary-admin");
        var assigned=store.assignEntitlement("u1",new AdminUserStore.EntitlementInput("pro","2026-11-15T09:00","2026-12-15T09:00","运营试用"),"temporary-admin");
        assertEquals("active",assigned.get("status"));
        var detail=store.detail("u1");
        assertEquals("pro",((java.util.Map<?,?>)detail.get("user")).get("tier"));
        store.revokeEntitlement("u1",String.valueOf(assigned.get("id")),"停止试用","temporary-admin");
        store.saveSettings(new AdminUserStore.SettingsInput("本周运营公告"),"temporary-admin");
        assertEquals("本周运营公告",store.settings().get("userAnnouncement"));
        assertEquals(4,((java.util.List<?>)store.audit().get("items")).size());
    }
}
