package com.fanzuixianchang.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fanzuixianchang.core.Problem;
import java.sql.SQLException;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MySqlOpsPersistenceTest {
 private JdbcDataSource database()throws Exception{
  var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
  try(var connection=source.getConnection();var sql=connection.createStatement()){
   sql.execute("CREATE TABLE fanzui_ops_state(namespace VARCHAR(32) PRIMARY KEY,revision BIGINT NOT NULL CHECK(revision>=0),payload LONGTEXT NOT NULL,updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6))");
  }return source;
 }
 @Test void savesAndReloadsUtf8AcrossNewInstances()throws Exception{
  var source=database();var mapper=new ObjectMapper();var first=new MySqlOpsPersistence(mapper,source);
  assertNull(first.load("content"));first.save("content",-1,"{\"title\":\"鸡腿饭与豆腐\"}");
  var second=new MySqlOpsPersistence(mapper,source);assertEquals(0,second.load("content").revision());assertEquals("鸡腿饭与豆腐",mapper.readTree(second.load("content").json()).get("title").asText());
  second.save("content",0,"{\"title\":\"新版本\"}");assertEquals(1,first.load("content").revision());
 }
 @Test void createAndUpdateConflictsDoNotOverwriteCommittedState()throws Exception{
  var source=database();var m=new ObjectMapper();var first=new MySqlOpsPersistence(m,source);var second=new MySqlOpsPersistence(m,source);
  first.save("workflows",-1,"{\"count\":1}");assertEquals(409,assertThrows(Problem.class,()->second.save("workflows",-1,"{\"count\":2}")).status());
  first.save("workflows",0,"{\"count\":3}");assertEquals(409,assertThrows(Problem.class,()->second.save("workflows",0,"{\"count\":4}")).status());
  assertEquals(3,m.readTree(second.load("workflows").json()).get("count").asInt());
  assertEquals(409,assertThrows(Problem.class,()->second.save("content",0,"{}")).status());
 }
 @Test void realStoresShareStateAndRejectStalePublicVersions()throws Exception{
  // 真 Store + 真 MySQL 底座：跨实例读到最新，过期版本号被拒。
  // 案卷那侧用 CaseFileStore，它和反馈共用 OpsPersistence，是这条语义的实际使用者。
  var ds=database();var mapper=new ObjectMapper();var validator=new CaseFileValidator(mapper);
  var a=new CaseFileStore(mapper,new MySqlOpsPersistence(mapper,ds),validator);
  var b=new CaseFileStore(mapper,new MySqlOpsPersistence(mapper,ds),validator);
  a.save(new CaseFileStore.Change(0,CaseFileFixtures.bundle("MySQL草稿")));
  assertEquals("MySQL草稿",b.get().draft().path("cases").get(0).path("title").asText(),"第二个实例要读到 a 刚存的草稿");
  assertEquals(409,assertThrows(Problem.class,()->b.publish(0)).status(),"旧版本号发不出去");
  b.publish(1);
  assertEquals("MySQL草稿",new CaseFileStore(mapper,new MySqlOpsPersistence(mapper,ds),validator)
    .published().path("cases").get(0).path("title").asText(),"换个进程也要读到同一份线上内容");
  var work=new OpsWorkStore(mapper,new MySqlOpsPersistence(mapper,ds));var feedback=work.createFeedback(new OpsWorkStore.FeedbackInput("other","中文反馈",true));
  assertEquals(feedback,new OpsWorkStore(mapper,new MySqlOpsPersistence(mapper,ds)).feedback().getFirst());
 }
 @Test void databaseFailureIsSanitizedAndNeverCreatesFallbackState(){
  var p=new MySqlOpsPersistence(new ObjectMapper(),(MySqlOpsPersistence.Connections)()->{throw new SQLException("jdbc:mysql://private-host?password=private-secret","08001");});
  var read=assertThrows(Problem.class,()->p.load("content"));assertEquals(503,read.status());assertFalse(read.getMessage().contains("private"));
  var write=assertThrows(Problem.class,()->p.save("content",-1,"{}"));assertEquals(503,write.status());assertFalse(write.getMessage().contains("private"));
 }
 @Test void namespacesJsonAndUrlsAreValidatedWithoutOpeningConnections()throws Exception{
  var p=new MySqlOpsPersistence(new ObjectMapper(),(MySqlOpsPersistence.Connections)()->{throw new AssertionError("invalid input must not connect");});
  assertThrows(IllegalArgumentException.class,()->p.load("content' OR 1=1"));assertThrows(IllegalArgumentException.class,()->p.save("workflows",-2,"{}"));
  assertEquals(503,assertThrows(Problem.class,()->p.save("content",-1,"[]")).status());
  assertThrows(IllegalArgumentException.class,()->new MySqlOpsPersistence(new ObjectMapper(),"jdbc:mysql://user:secret@host/db","user","password"));
  assertThrows(IllegalArgumentException.class,()->new MySqlOpsPersistence(new ObjectMapper(),"jdbc:mysql://host/db?password=private-secret","user","password"));
  assertThrows(IllegalArgumentException.class,()->new MySqlOpsPersistence(new ObjectMapper(),"jdbc:mysql://host/db?connectTimeout=0","user","password"));
 }
}
