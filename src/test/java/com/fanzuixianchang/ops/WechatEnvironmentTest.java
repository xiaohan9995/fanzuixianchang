package com.fanzuixianchang.ops;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class WechatEnvironmentTest {
 private ConfigurableEnvironment environment(Map<String,Object> values)throws Exception{var env=new StandardEnvironment();env.getPropertySources().addFirst(new YamlPropertySourceLoader().load("app",new ClassPathResource("application.yml")).get(0));env.getPropertySources().addFirst(new MapPropertySource("test-values",values));return env;}
 @Test void usesWechatInjectedMysqlVariables()throws Exception{var e=environment(Map.of("MYSQL_ADDRESS","mysql.internal:3306","MYSQL_DATABASE","fanzui_test","MYSQL_USERNAME","runtime","MYSQL_PASSWORD","test-password"));assertEquals("jdbc:mysql://mysql.internal:3306/fanzui_test?useUnicode=true&characterEncoding=UTF-8",e.getProperty("app.ops.mysql-url"));assertEquals("runtime",e.getProperty("app.ops.mysql-user"));assertEquals("test-password",e.getProperty("app.ops.mysql-password"));}
 @Test void explicitOpsSettingsTakePrecedence()throws Exception{var e=environment(Map.of("MYSQL_ADDRESS","mysql.internal:3306","OPS_MYSQL_URL","jdbc:mysql://other.internal:3306/other_db","OPS_MYSQL_USER","ops","OPS_MYSQL_PASSWORD","other-password"));assertEquals("jdbc:mysql://other.internal:3306/other_db",e.getProperty("app.ops.mysql-url"));assertEquals("ops",e.getProperty("app.ops.mysql-user"));assertEquals("other-password",e.getProperty("app.ops.mysql-password"));}
}
