# 饭“罪”现场 · 微信云托管后端

由微信云托管 Spring Boot 模板改造，保留80端口、Docker源码构建和内置MySQL变量约定。业务使用Java21、Spring Boot3.5.16、Spring AI1.1.8，包含健康饮食核算、饭探AI、运营后台和私有对象存储。

## 微信云托管部署

目标环境：上海 `prod-d3gzwdsmye6d687f1`。在微信云托管中创建或选择服务 `fanzuixianchang`，关联本仓库`master`，构建目录为仓库根目录，Dockerfile为`Dockerfile`，端口**80**。镜像使用多阶段构建，运行时采用非root用户。

首次模板部署可使用`container.config.json`初始化服务与数据库；它不是后续服务设置的同步配置。已存在的服务需在控制台设置端口、环境变量并执行下面的SQL迁移。

## MySQL

生产容器默认`OPS_STORAGE=mysql`。支持云托管注入的变量：

| 变量 | 用途 |
| --- | --- |
| `MYSQL_ADDRESS` | 内网host:port |
| `MYSQL_DATABASE` | 数据库，默认`fanzuixianchang` |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 数据库运行凭据 |

也可显式配置`OPS_MYSQL_URL`、`OPS_MYSQL_USER`、`OPS_MYSQL_PASSWORD`覆盖上述值。密码不得放在JDBC URL或提交到仓库。

部署前在选定数据库执行[src/main/resources/db/mysql/001_ops_state.sql](src/main/resources/db/mysql/001_ops_state.sql)。先检查是否存在同名表及其结构；不要覆盖已有数据。业务请求不自动建表，运行账号仅需SELECT/INSERT/UPDATE。云托管服务与MySQL需内网可达。

MySQL保存内容版本、反馈、资料审核与操作记录。每次读取最新状态，写入通过revision原子比较，冲突返回409，失败返回503，不回退到容器文件。原有CloudBase PG适配保留以支持数据迁移；切换存储不会自动搬运旧数据。迁移需停写、导出、导入并核对版本后切换，勿删除原环境。

## AI、微信登录和管理后台

服务端环境变量：

- `DEEPSEEK_API_KEY`，可选`DEEPSEEK_BASE_URL`，模型`deepseek-flash`。
- `WECHAT_APP_ID=wx951ec7e70fd3fd43`及对应`WECHAT_APP_SECRET`。
- `SESSION_SIGNING_SECRET`与`OPS_ADMIN_TOKEN`：分别生成至少32字符的独立随机值。

凭据仅从运行环境读取。仓库不包含真实密钥、个人餐食、反馈数据或本机配置。未配置AI时相关接口返回503；未配置管理员令牌时管理接口关闭。

后台地址`/ops/index.html`，根路径自动引导到后台。提供问题草稿/发布/回滚、反馈处理、食物资料审核及运行统计。后台浏览器正式访问应绑定自定义HTTPS域名；不要把平台测试域名作为正式入口。

小程序使用`wx.cloud.callContainer`，环境`prod-d3gzwdsmye6d687f1`，`X-WX-SERVICE: fanzuixianchang`。当前AppID必须获准使用目标环境。后端保留自己的会话认证，不依赖客户端自报openid。

## 对象存储（COS）

默认`COS_ENABLED=false`。配置私有存储桶后开启：

`COS_ENABLED=true`、`COS_BUCKET`、`COS_REGION=ap-shanghai`、`COS_SECRET_ID`、`COS_SECRET_KEY`；使用临时凭据时另加`COS_SESSION_TOKEN`，到期需安全轮换。使用限制到目标桶`private/users/*`前缀的服务端凭据，勿把密钥给小程序。

| 接口 | 行为 |
| --- | --- |
| `POST /api/storage/files` | `{mimeType,base64,consent:true}`上传JPEG/PNG，最大2MiB；返回文件id |
| `GET /api/storage/files/{id}` | 仅当前账号可取得5分钟签名下载地址 |
| `DELETE /api/storage/files/{id}` | 删除当前账号的文件，重复删除安全 |

文件私有ACL，按用户摘要与随机UUID隔离。不会自动上传本机照片。当前通过COS对象元数据管理已知文件id，不提供MySQL文件列表或自动照片云同步。

## 本地运行与验证

```sh
mvn -s settings.xml test
mvn -s settings.xml package
OPS_STORAGE=file java -jar target/fanzuixianchang-api-0.1.0.jar
```

本地默认监听127.0.0.1:8080，容器监听0.0.0.0:80；均可用`SERVER_ADDRESS`/`PORT`覆盖。健康接口`GET /api/health`，不输出秘密。`.env.example`仅说明变量，应用不自动加载.env；请由运行平台或shell注入。

51项JUnit及打包在改造工作区通过。MySQL行为由H2 MySQL模式验证，COS行为由可控对象存储替身验证；**新微信环境真实MySQL、COS桶、真机调用仍需配置后联调**。原腾讯云环境的测试结果不等同新环境已部署成功。

营养目录含USDA来源及中文映射，详见`src/main/resources/nutrition/provenance.json`与`SOURCES.md`。保留原模板LICENSE。

## 云端构建依赖源

Docker构建使用settings.xml中的腾讯云Maven镜像，避免共享云构建出口触发Maven Central 429。打包步骤始终通过镜像解析依赖，并对网络传输启用有限重试。镜像中不包含仓库认证信息。
