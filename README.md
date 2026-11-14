# 饭“罪”现场 · 微信云托管后端

由微信云托管 Spring Boot 模板改造，保留80端口、Docker源码构建和内置MySQL变量约定。业务使用Java21、Spring Boot3.5.16、Spring AI1.1.8，包含健康饮食核算、饭探AI、运营后台和私有对象存储。

## 微信云托管部署

部署到上海微信云托管环境`fanzuixianchang-d4fngffte8142a82`。服务名`fanzuixianchang`，关联本仓库`master`，构建目录为仓库根目录，Dockerfile为`Dockerfile`，端口**80**。镜像使用多阶段构建，运行时采用非root用户。

首次模板部署可使用`container.config.json`初始化服务与数据库；它不是后续服务设置的同步配置。已存在的服务需在控制台设置端口、环境变量并执行下面的SQL迁移。

## MySQL

生产容器默认`OPS_STORAGE=mysql`。支持云托管注入的变量：

| 变量 | 用途 |
| --- | --- |
| `MYSQL_ADDRESS` | 内网host:port |
| `MYSQL_DATABASE` | 数据库，默认`fanzuixianchang` |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 数据库运行凭据 |

也可显式配置`OPS_MYSQL_URL`、`OPS_MYSQL_USER`、`OPS_MYSQL_PASSWORD`覆盖上述值。密码不得放在JDBC URL或提交到仓库。

部署前检查现有表，再在选定数据库执行[src/main/resources/db/mysql/001_ops_state.sql](src/main/resources/db/mysql/001_ops_state.sql)和[src/main/resources/db/mysql/002_user_accounts_and_sync.sql](src/main/resources/db/mysql/002_user_accounts_and_sync.sql)。应用不自动建表。运营表需SELECT/INSERT/UPDATE；用户、饭案与会员数据表需SELECT/INSERT/UPDATE/DELETE。云托管服务与MySQL需内网可达。

MySQL保存内容版本、反馈、资料审核、操作记录、账号身份映射、授权记录、饭案与会员数据。原有CloudBase PG适配保留以支持迁移；切换存储不会自动搬运旧数据。迁移需停写、导出、导入并核对版本后切换，勿删除仍含有效数据的环境。

## AI、微信登录和管理后台

服务端环境变量：

- `DEEPSEEK_API_KEY`，可选`DEEPSEEK_BASE_URL`，模型`deepseek-flash`。
- `WECHAT_APP_ID`及对应`WECHAT_APP_SECRET`。
- `SESSION_SIGNING_SECRET`、`USER_IDENTITY_SECRET`与`OPS_ADMIN_TOKEN`：分别生成至少32字符的独立随机值。`USER_IDENTITY_SECRET`需长期稳定，用于OpenID的HMAC映射，轮换需先迁移身份数据。

小程序使用`wx.cloud.callContainer`访问云托管；登录沿用`wx.login → 服务端 code2session → HMAC签名会话`，OpenID和密钥不会返回小程序。所有凭据只从服务端运行环境读取。新环境尚未部署和验证前，不要宣称云端登录或同步已上线。

后台地址`/ops/index.html`，根路径自动引导到后台。提供问题草稿/发布/回滚、反馈处理、食物资料审核及运行统计。后台浏览器正式访问应绑定自定义HTTPS域名；不要把平台测试域名作为正式入口。

## 账号、饭案同步与会员底座

- `POST /api/session`：将`wx.login`临时code交给服务端兑换身份；不返回OpenID或session_key。
- `GET /api/account`：读取内部账号ID、会员权益摘要、积分余额及云同步状态。
- `GET/POST /api/account/sync/consent`：读取或变更饭案云同步的单独授权，授权会写入留痕。
- `GET/PUT/DELETE /api/account/meals`及`DELETE /api/account/meals/{id}`：按账号读取、带版本冲突检测保存或删除饭案。每次最多同步20条；图片和本机路径不上传。
- `GET /api/account/export`、`DELETE /api/account`：导出云端账号数据或删除账号及关联云数据。

饭案默认保存在用户本机，云同步须由用户单独开启。健康档案、目标、口味偏好及图片不会自动上传。会员套餐、订单、权益和积分流水目前只有数据表与只读摘要，不开放支付、购买或积分增减。

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

本地纯JDK核心校验及之前已完成的Maven构建可查看项目开发记录。新环境的MySQL/VPC、COS桶、小程序登录与真机调用仍需在资源创建并关联后联调。

营养目录含USDA来源及中文映射，详见`src/main/resources/nutrition/provenance.json`与`SOURCES.md`。保留原模板LICENSE。

## 云端构建依赖源

Docker构建使用settings.xml中的腾讯云Maven镜像，避免共享云构建出口触发Maven Central 429。打包步骤通过镜像解析依赖，并对网络传输启用有限重试。镜像中不包含仓库认证信息。
