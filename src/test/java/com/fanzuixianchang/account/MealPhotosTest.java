package com.fanzuixianchang.account;
import com.fanzuixianchang.core.Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;import java.util.*;import java.time.Instant;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class MealPhotosTest {
 final ObjectMapper mapper=new ObjectMapper();Connection c;MealPhotos photos;
 @BeforeEach void setup()throws Exception{c=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL");try(var q=c.createStatement()){q.execute("CREATE TABLE fanzui_users(user_id VARCHAR(36) PRIMARY KEY,status VARCHAR(16))");q.execute("INSERT INTO fanzui_users VALUES('u1','active'),('u2','active')");q.execute("CREATE TABLE fanzui_user_consents(user_id VARCHAR(36),purpose VARCHAR(40),granted BOOLEAN)");q.execute("INSERT INTO fanzui_user_consents VALUES('u1','meal_cloud_sync',true),('u1','meal_photo_sync',true)");q.execute("CREATE TABLE fanzui_meal_photos(file_id VARCHAR(36) PRIMARY KEY,user_id VARCHAR(36),record_id VARCHAR(120),cloud_file_id VARCHAR(255),mime_type VARCHAR(32),state VARCHAR(16),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");}c.setAutoCommit(false);photos=new MealPhotos(mapper);}
 @AfterEach void close()throws Exception{c.close();}
 String upload()throws Exception{String id=photos.register(c,"u1","meal-1","cloud://env.bucket/ai/u-1/1.jpg","image/png");c.commit();return id;}
 com.fasterxml.jackson.databind.node.ObjectNode record(String id){var payload=mapper.createObjectNode();var array=payload.putArray("cloudImages");if(id!=null)array.addObject().put("fileId",id).put("mimeType","image/png");return payload;}
 @Test void privateReadAndRecordOwnership()throws Exception{String id=upload();photos.attach(c,"u1","meal-1",record(id));c.commit();assertEquals(id,photos.read(c,"u1",id).get("fileId"));assertEquals("cloud://env.bucket/ai/u-1/1.jpg",photos.read(c,"u1",id).get("cloudFileId"));assertThrows(Problem.class,()->photos.read(c,"u2",id));assertThrows(Problem.class,()->photos.attach(c,"u1","different-meal",record(id)));}
 @Test void 餐图只收私有目录而临时目录只为兼容存量()throws Exception{
  // 新客户端写到私有餐图目录
  String id=photos.register(c,"u1","meal-1","cloud://env.bucket/private/meal-photos/u-1/1.jpg","image/png");
  c.commit();
  assertEquals("cloud://env.bucket/private/meal-photos/u-1/1.jpg",photos.read(c,"u1",id).get("cloudFileId"));

  // 早期客户端的 ai/ 路径仍可登记，否则在线用户升级前会传不上图
  assertDoesNotThrow(()->photos.register(c,"u1","meal-2","cloud://env.bucket/ai/u-1/2.jpg","image/png"));

  // 其余路径一律不收：公共内容目录、别人的目录、根目录
  assertThrows(Problem.class,()->photos.register(c,"u1","meal-3","cloud://env.bucket/content/cases/a-001/x.jpg","image/png"));
  assertThrows(Problem.class,()->photos.register(c,"u1","meal-4","cloud://env.bucket/private/users/abc/x.jpg","image/png"));
  assertThrows(Problem.class,()->photos.register(c,"u1","meal-5","cloud://env.bucket/x.jpg","image/png"));
  assertThrows(Problem.class,()->photos.register(c,"u1","meal-6","cloud://env.bucket/private/meal-photos/../users/x.jpg","image/png"));
 }
 @Test void 客户端与服务端可以分开升级()throws Exception{
  // 线上会有一段时间是「新客户端 + 旧服务端」或反过来。四种组合都得能用：
  //
  //   旧客户端 (ai/) + 旧服务端 (只验 cloud://)      → 线上现状
  //   新客户端 (private/meal-photos/) + 旧服务端      → 先发小程序时
  //   旧客户端 (ai/) + 新服务端 (白名单含 ai/)         → 先发服务端时
  //   新客户端 (private/meal-photos/) + 新服务端       → 最终态
  //
  // 旧服务端的判定逻辑是 id.startsWith("cloud://")，对新路径同样成立，
  // 所以「新客户端 + 旧服务端」不需要额外兼容代码——这里断言的是
  // 新服务端不能反过来把旧客户端挡在门外。

  // 旧客户端上传的 ai/ 路径：新服务端必须继续接受，否则升级服务端那一刻
  // 所有还没更新的用户都传不上照片。
  String legacy=photos.register(c,"u1","meal-legacy","cloud://env.bucket/ai/u-1/a.jpg","image/png");
  c.commit();
  assertEquals("cloud://env.bucket/ai/u-1/a.jpg",photos.read(c,"u1",legacy).get("cloudFileId"),
    "旧路径的存量记录要能读");

  // 新客户端上传的私有路径：新服务端接受。
  String fresh=photos.register(c,"u1","meal-fresh","cloud://env.bucket/private/meal-photos/u-1/b.jpg","image/png");
  c.commit();
  assertEquals("cloud://env.bucket/private/meal-photos/u-1/b.jpg",photos.read(c,"u1",fresh).get("cloudFileId"));

  // 同一条记录里混着新旧两代图片——用户升级过程中会这样。
  // 照片只能挂到登记时那个 record 上（防止挪用别人的图），所以按 meal-mixed 重新登记。
  String legacy2=photos.register(c,"u1","meal-mixed","cloud://env.bucket/ai/u-1/c.jpg","image/png");
  String fresh2=photos.register(c,"u1","meal-mixed","cloud://env.bucket/private/meal-photos/u-1/d.jpg","image/png");
  c.commit();
  var payload=mapper.createObjectNode();var array=payload.putArray("cloudImages");
  array.addObject().put("fileId",legacy2).put("mimeType","image/png");
  array.addObject().put("fileId",fresh2).put("mimeType","image/png");
  photos.attach(c,"u1","meal-mixed",payload);
  c.commit();
  assertEquals(2,payload.path("cloudImages").size(),"两代图片可以共存在同一份饭案里");
 }
 @Test void explicitConsentAndFileIdAndMimeAreRequired()throws Exception{assertThrows(Problem.class,()->photos.register(c,"u2","meal-1","cloud://env.bucket/a/1.jpg","image/png"));assertThrows(Problem.class,()->photos.register(c,"u1","meal-1","not-a-cloud-id","image/png"));assertThrows(Problem.class,()->photos.register(c,"u1","meal-1","cloud://env.bucket/a/1.jpg","text/html"));}
 @Test void replacingPhotoQueuesDeletionAndRowIsRemoved()throws Exception{String id=upload();photos.attach(c,"u1","meal-1",record(id));c.commit();photos.attach(c,"u1","meal-1",record(null));c.commit();assertEquals(1,photos.cleanup(c));assertThrows(Problem.class,()->photos.read(c,"u1",id));}
 @Test void deletingAccountKeepsDurableCleanupJob()throws Exception{String id=upload();photos.queueDelete(c,"u1",null);try(var q=c.createStatement()){q.execute("DELETE FROM fanzui_users WHERE user_id='u1'");}c.commit();assertEquals(1,photos.cleanup(c));}
 @Test void oldClientsPreserveReferencesAndOrphansExpire()throws Exception{String id=upload();photos.attach(c,"u1","meal-1",record(id));c.commit();var legacy=mapper.createObjectNode();photos.attach(c,"u1","meal-1",legacy);c.commit();assertEquals(id,legacy.path("cloudImages").get(0).path("fileId").asText());String orphan=upload();try(var q=c.prepareStatement("UPDATE fanzui_meal_photos SET created_at=? WHERE file_id=?")){q.setTimestamp(1,Timestamp.from(Instant.now().minusSeconds(90000)));q.setString(2,orphan);q.executeUpdate();}c.commit();assertEquals(1,photos.cleanup(c));assertEquals(id,photos.read(c,"u1",id).get("fileId"));assertThrows(Problem.class,()->photos.read(c,"u1",orphan));}
}
