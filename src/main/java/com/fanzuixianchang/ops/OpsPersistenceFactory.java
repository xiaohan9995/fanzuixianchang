package com.fanzuixianchang.ops;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.Path;
@Configuration
public class OpsPersistenceFactory {
 @Bean public OpsPersistence opsPersistence(ObjectMapper mapper,@Value("${app.ops.storage:file}") String mode,@Value("${app.ops.data-dir:./data/ops}") String dir,@Value("${app.ops.cloudbase-env:}") String env,@Value("${app.ops.cloudbase-api-key:}") String key,@Value("${app.ops.mysql-url:}") String mysqlUrl,@Value("${app.ops.mysql-user:}") String mysqlUser,@Value("${app.ops.mysql-password:}") String mysqlPassword){return switch(mode){case "file"->new FileOpsPersistence(mapper,Path.of(dir));case "cloudbase-pg"->new CloudBasePgPersistence(mapper,env,key);case "mysql"->new MySqlOpsPersistence(mapper,mysqlUrl,mysqlUser,mysqlPassword);default->throw new IllegalArgumentException("Unknown operations storage mode");};}
}
