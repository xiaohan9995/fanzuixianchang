# 饭罪现场 Spring AI 后端

原生小程序的真实 AI 调查接口。使用 **Java 21+、Spring Boot 3.5.16、Spring AI 1.1.8**，通过 Spring AI 的 OpenAI-compatible 适配层调用 **DeepSeek V4.1 Flash**。模型 ID 为 `deepseek-flash`，不是自行拼接的版本名称；无需 OpenAI 账号或 OpenAI 密钥。

## 唯一后端仓库与云托管

本目录是饭罪现场唯一的后端源码与 Git 仓库。小程序、规划文档仍在上层产品工作区；后端代码只在本仓库维护。当前微信云托管环境为上海 `fanzuixianchang-d4fngffte8142a82`，服务名 `fanzuixianchang`，容器监听端口8080。

运行镜像以非root用户`app`启动，因此使用高位端口8080；不要把容器端口设置为80，否则 Linux 会因非root进程无权绑定特权端口而抛出`java.net.BindException: Permission denied`。云托管服务设置中的监听端口也必须设为8080，与镜像保持一致。

部署使用本仓库根目录的 `Dockerfile`。云托管服务需设置 `OPS_STORAGE=mysql`、`MYSQL_ADDRESS`、`MYSQL_DATABASE`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`，并配置 `WECHAT_APP_ID`、`WECHAT_CLOUDRUN_ENV_ID`、`USER_IDENTITY_SECRET` 和 `DEEPSEEK_API_KEY`。`WECHAT_CLOUDRUN_ENV_ID` 必须与云托管环境 ID 完全一致；`USER_IDENTITY_SECRET` 至少32字符且需长期稳定。密钥只在云托管服务设置中配置，不提交 Git。云托管免 code 登录不需要 `WECHAT_APP_SECRET` 或 `SESSION_SIGNING_SECRET`。

云托管 MySQL 不会由应用自动建表。确认服务已连接正确数据库后，先检查已有表，再执行 `src/main/resources/db/mysql/001_ops_state.sql` 与 `src/main/resources/db/mysql/002_user_accounts_and_sync.sql`。手动创建服务时，需要从云托管 MySQL 控制台获取并配置地址、用户名、密码；仅把模板仓库连接到 Git 并不会自动生成这些运行变量。使用 TCP 连接 MySQL 时，还需配置服务到 MySQL 所在网络的访问关系。

## 已实现

- `GET /api/health`：服务状态、模型名与是否已配置 AI；无密钥时可以启动并返回 `configured:false`。
- `GET /api/session`：验证微信云托管注入的 `X-WX-OPENID`、`X-WX-APPID`、`X-WX-ENV` 请求头，并创建或恢复饭罪现场账号；不调用 `wx.login`、不下发自定义令牌，也不保存明文 OpenID。
- `GET /api/account`：读取内部账号、当前会员权益与积分摘要；用户身份映射和业务记录保存在 MySQL，OpenID 只做服务端 HMAC 映射，不写入用户表。
- `POST /api/account/sync/consent`、`GET/PUT/DELETE /api/account/meals`：单独授权后的饭案云同步、版本冲突检测和删除。只同步饭案，不同步健康档案、饮食目标、口味偏好或本机图片路径。
- `GET /api/account/export`、`DELETE /api/account`：导出账号与饭案数据、注销并删除账号数据。
- `GET /api/foods?q=米饭`：本地 USDA SR Legacy 2018 基础食物目录搜索，最多20条；返回原始英文做法、每100g能量与来源。
- `POST /api/nutrition/calculate`：按已确认的目录条目、食用量区间或包装标签进行确定性换算；未确认食物保留未知，返回已知小计和完整性。
- `POST /api/goals/estimate`：普通成人维持能量参考，含适用筛查、公式版本与假设，不自动设置减脂缺口。
- `POST /api/cases/analyze`：校验云托管注入身份、真实图片与文字、多条件线索、限定知识条目、结构化饭案、后续追问和输出校验。
- 标签换算由 `BigDecimal` 确定计算，覆盖模型生成的 calculation；未知营养数值不凭照片猜测。数量化营养/能量断言不能借用用户提供的食物克数混入结论、推荐或追问回答；食物份量仅在 `foods.amount` 与明确归于用户的证据中保留。
- 吃前候选的标价只从用户当前文字中与候选名称直接关联的片段核对，不能从独立预算数字或模型输出取价。重复且矛盾价格保留未知；人均预算需已知人数才能换算整桌预算。忌口只对当前用户文字中明确的正向食材冲突作保守标记。**自由文本的“无/不含/不放”等表述、双重否定、部分去除描述均不能认证食材不存在**；首版没有结构化、人工确认的完整配料机制，所以凡有忌口要求又无明确正向冲突的候选，一律保持待核对（null），不成为已核对主推荐，仍可条件性比较。
- 规范化候选可附 `price`、`eligible`（true已核对/false明确冲突/null仍待核对）、`conflicts`，报告附 `recommendationId`（只有已核对候选可作为主推荐）。已知冲突候选不被主推荐；未知候选仍可在保留条件下比较，不能被写成“符合预算/忌口”的保证。
- H01/H02 来源由服务端生成规范标题与链接，模型不能提供任意网址。记忆关闭后请求中不使用其旧偏好，不使用服务端共享会话历史。
- 追问可携带 `context={report,selectedChoiceId,turns}`，只接受允许字段、最多3轮对话、序列化总量不超过12000字符。上下文在提示词中明确标为暂定报告/对话而非新增证据；当前输入与当前偏好优先，普通分析不会使用隐式对话历史。
- 每张图片最多2 MiB、最多3张，9 MiB请求上限；MIME和文件头核对，不从任意图片 URL 抓取数据。每身份15次分析/分钟，目录/换算/目标接口另计120次/分钟且请求最多64 KiB；全局2个模型调用并行、单次模型请求55秒读超时、10秒连接超时、至多1次尝试。
- 不记录图片、完整饮食对话、微信登录响应或上游错误正文。饭案默认仍保存在小程序本机；用户单独同意后，饭案文本和分析结果可按账号同步。健康档案、目标、口味和图片仍只保存在本机。

## 用户与会员数据

云托管 MySQL 除运营表外还需执行 `src/main/resources/db/mysql/002_user_accounts_and_sync.sql`。运行账号需对这些表具备 SELECT/INSERT/UPDATE/DELETE；不得给客户端数据库凭据。服务端需设置独立且长期稳定的 `USER_IDENTITY_SECRET`（至少32字符），用于 OpenID 的 HMAC 映射；改变该值会使已有用户映射不可识别，需先做迁移。

会员套餐、订单、权益和积分流水表仅为数据底座。本版本不开放支付、购买、积分增减或等级规则；所有用户看到的初始权益为免费档，积分为0。新 CloudRun 环境和 MySQL 尚未完成真实部署时，不应宣称云同步已上线。

## 可选私有对象存储（COS）

默认 `COS_ENABLED=false`。配置私有存储桶后，可设置 `COS_ENABLED=true`、`COS_BUCKET`、`COS_REGION=ap-shanghai`、`COS_SECRET_ID`、`COS_SECRET_KEY`；临时凭据另设 `COS_SESSION_TOKEN`。服务端凭据限制到目标桶和 `private/users/*` 前缀，不交给小程序。

| 接口 | 行为 |
| --- | --- |
| `POST /api/storage/files` | 用户明确同意后上传 JPEG/PNG，最大2MiB，返回文件ID |
| `GET /api/storage/files/{id}` | 仅当前账号获得5分钟签名下载地址 |
| `DELETE /api/storage/files/{id}` | 删除当前账号文件，重复删除安全 |

餐食照片不会因开启饭案同步而自动上传 COS。

规则检查和来源 ID 校验只能限制部分输出，**不能证明临床正确性或自动验证所有健康表述**。候选约束是对明确文字价格与已知食材词的保守校验，不是对任意菜单、做法或自然语言的完整事实验证；图像识别结果也不自动成为已确认价格或配料。知识条目是一般健康教育资料的有限整理，尚需专业内容审核与真实模型评测。

## 启动

1. 安装 Java 21+ 与 Maven 3.6.3+，设置环境变量；可参考 `.env.example`。脚本会优先使用 PATH 中 Maven，也能识别本机 IntelliJ 捆绑 JDK/Maven。
2. AI 调查需要 `DEEPSEEK_API_KEY`；目录、换算、目标接口无需模型密钥。云托管身份校验需要 `WECHAT_APP_ID`、`WECHAT_CLOUDRUN_ENV_ID` 和至少32字符的 `USER_IDENTITY_SECRET`。
3. 在仓库根目录运行 `./run.sh`。`.env` 不会自动加载；使用当前 shell 的 export 或部署平台环境变量，禁止把实际密钥填入小程序。

本机模拟器联调可显式设置 `SPRING_PROFILES_ACTIVE=local` 与自己生成的至少24字符 `BACKEND_DEV_TOKEN`，保持 `SERVER_ADDRESS=127.0.0.1`。然后在小程序搭档设置填 `http://127.0.0.1:8080` 与同一调试令牌。**local profile 不可经公网反向代理暴露**。云托管正式请求必须由小程序 `wx.cloud.callContainer` 发起，以便微信注入身份请求头；直接 HTTPS 请求不会获得云托管身份。

正式环境不启用 local profile；设置必要微信身份配置与云托管关联。身份密钥换新会使现有 OpenID 映射无法识别，变更前需迁移。多实例部署前，把内存限流迁至统一网关；当前限流为单进程保护。

## 请求示例

```json
{
  "caseId":"case-1","revision":1,"mode":"before","scene":"solo",
  "text":"两份鸡腿饭，想在预算内选一份不辣的。",
  "conditions":{"budget":25,"budgetType":"total","dislikes":["辣"],"priority":"口味"},
  "images":[],"preferences":{"memoryEnabled":false}
}
```

图片字段为 `{"mimeType":"image/jpeg","base64":"实际图片的base64"}`，不含 `data:` 前缀，不含本地路径。mode 为 before、after 或 label；scene 为 solo、group 或 night。label 需要 conditions.per100、amount、unit（kJ/kcal/g）；数字只能在用户核对数据后解释为实际食用结果。

响应为 `{report,answer}`。group 始终返回 table scope，不能让模型把整桌转换为个人食用。

## 验证

```sh
./scripts/check-core.sh
# 依赖可用时运行完整 Java 测试与打包
mvn -s settings.xml test
```

`check-core.sh` 使用纯 JDK 编译核心校验、计算、来源、会话与限流测试，不需要联网下载依赖。当前包括24项基础行为、35项独立评审回归检查与28项营养/目标/每日上下文检查（87项），涉及份量不能被改写为营养素数量、预算/忌口冲突与未知状态、双重否定、零预算和有界追问上下文。Maven 测试覆盖这些核心行为、API 错误约定与多模态模型消息。真实 DeepSeek 与微信换码测试需要专属配置，默认测试不消耗实际模型额度。

2026-11-14：纯 JDK 87项检查和 Maven 13项测试、可执行 JAR 打包通过。真实 DeepSeek 与微信换码未执行；需要专属配置才能验证。部分受限沙箱禁止 Mockito 的 JVM agent attach，可在允许本地 JVM 测试进程的环境运行完整构建。

## 营养目录与确定性接口

目录包含7793条 USDA SR Legacy 2018-04 食物，不提供当前品牌、外卖菜谱或中国餐馆菜品的营养认证。46条常见基础食物有人工核对的中文名称/别名；别名只指向指定 FDC ID，原始英文名称和烹饪状态随响应保留。豆乳饮料不直接当作中式豆浆，花菜对应花椰菜，不等同西兰花。目录质量仍需专业审阅，包装商品优先核对实际标签。

[来源元数据](src/main/resources/nutrition/provenance.json)记录官方地址、CC0/public domain依据、源 ZIP 与产物 SHA-256。仓库保留精简 TSV 和复现脚本，不需要每次启动联网下载。

```sh
curl -L --fail 'https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_json_2018-04.zip' -o /tmp/usda-sr2018.zip
python3 scripts/import-usda.py /tmp/usda-sr2018.zip
```

`/api/nutrition/calculate` 接收 `{items:[...]}`，最多30项。每项须有唯一 `id`、用户名称 `name` 和 `type`。`food` 项用 `foodId`、`amountMin`、`amountMax`、`unit:"g"`；`label` 项另有 `label:{per100,energyUnit:"kcal"|"kJ",basisUnit:"g"|"mL"}`，食用量单位必须匹配标签。`unknown` 项保持 `energyKcal:null`。服务端只按目录和标签输入计算，忽略调用方伪造的目录热量。响应 `energyKcal:{min,max}` 为已知小计，必须结合 `complete`、`unknownCount` 使用；不能将未知项目解释为零。kJ以4.184换算。

`/api/goals/estimate` 接收 `age`、`sex`（male/female为公式参数）、`heightCm`、`weightKg`、`activityFactor`，以及 `screening` 的 `pregnantOrBreastfeeding`、`medicalNutrition`、`eatingDisorder` 三项布尔值。输入边界为年龄0–120整数、身高80–250cm、体重20–350kg、活动系数1.4–2.5；有效输入仍须通过18–64岁、BMI≥18.5且无筛查标记才返回参考值。静息估算为 `10×kg+6.25×cm−5×年龄+(男5/女−161)`，乘活动系数后按50kcal取整。此处是尚未专业审阅的维持参考，不等同于NIDDK完整动态模型，也不保证适合每个人。

吃前 AI 调查可附 `dailyContext:{date,goalKcal,recordedEnergyKcal:{min,max},unknownCount,coverageComplete}`；日期须为有效ISO日历日期，目标可为null。服务端只允许这些字段，并把上下文标为暂定记录，仅供定性建议。模型不能替工具计算、生成热量预算或诊断；吃后模式不使用每日上下文。

## 版本与接口依据

- [Spring Boot 官方系统要求与受支持稳定版本](https://docs.spring.io/spring-boot/system-requirements.html) 列出 3.5.16。
- [Spring AI 1.1.8 ChatClient 文档](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html) 确认维护中的1.1分支；使用1.1.8源码核验 OpenAiChatModel、OpenAiApi、Media 与 UserMessage API。
- [Spring AI 1.1.8 OpenAiChatModel 源码](https://github.com/spring-projects/spring-ai/blob/v1.1.8/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java)。使用 Spring AI 真正的 ChatModel，不以手写 HTTP 假冒框架接入。
- [DeepSeek V4.1 Flash 发布说明](https://api-docs.deepseek.com/news/news260910/)、[官方图像接口](https://api-docs.deepseek.com/guides/vision/)、[JSON 输出要求](https://api-docs.deepseek.com/guides/json_mode/)。原生 DeepSeek Spring AI 适配器的媒体兼容性未验证，故选已核验支持 Media 的 OpenAI-compatible 适配器，目标地址仍为 DeepSeek。
- [微信 code2Session 官方文档](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-login/code2Session.html)。

以上为2026-11-14核验的版本与接口；营养与忌口规则边界见 [POLICY.md](POLICY.md)，食物目录来源见 [SOURCES.md](SOURCES.md)。

## 专业饮食版：营养素与食物线索识别

营养计算版本升级为 `nutrition-v2`，保留原有能量字段。每项与总计增加 `proteinG`、`fatG`、`carbG`（`{min,max}`或`null`），以及总计的 `nutrientUnknownCounts`。有部分已知项时返回已知小计并标注缺失数量，全部缺失为null；明确0与未填不同。`complete`/`unknownCount`仍只表示能量完整度，不能推断营养素齐全。标签可选 `proteinGPer100`、`fatGPer100`、`carbGPer100`，按标签同一100g/100mL口径录入，有限0–1000g，不反推数值或强制4/4/9等于标签能量。

经过核对的基础条目增加 `foodGroup`（staple/vegetable/fruit/protein/dairy/oil/other）；其他条目为unknown，复合菜不自动归类。分类是指定基础食物的语义标签，不是营养充分或医疗判断。

`POST /api/cases/identify-foods`：复用微信会话/本机授权、图片校验、AI限流与并发限制，接收 `{caseId,revision,text,images,scene}`；scene仅solo/night，图片最多3张、每张2MiB。缺少模型配置返回503，不返回伪造识别结果。请求不需要健康档案、饮食目标或历史记录。

响应为 `{version:"food-identification-v1",items:[{id,name,preparation,portion,candidates,unknowns}]}`。每项最多3个实际目录候选；模型不能指定食物ID或营养值。`portion`为null或 `{min,max,unit:"g",basis,origin:"user"|"image-estimate"}`，全部属于待用户确认线索。用户来源份量须与文本中的克数相符；图片估计须有图像、参照说明且不采用默认碗重；无法支持的份量丢弃为null。来源于图像的参照本身未经过专业几何测量验证，始终需要用户核对实际食用量。不能从订单或餐盘推定全部吃完。

提示词与解析对识别输出采用单独结构，普通AI报告对营养数字的约束未放宽。识别仅节省录入步骤，计算仍走经过确认条目的确定性接口。实际DeepSeek识别质量尚需项目密钥和真实餐图验证；自动测试使用模拟ChatModel，不消耗模型额度。

本轮复审后，独立Java检查共127项，Maven21项JUnit及可执行JAR构建通过；份量需绑定对应食物，明确否定参照时保留未知。小程序体验与端到端验收记录保留在产品工作区。

## 运营管理测试版

Spring服务同时提供 `/ops/index.html`，页面本身公开，管理API使用独立 `OPS_ADMIN_TOKEN`（至少32字符）认证。未配置时管理API返回503，普通用户会话或开发令牌不能访问。管理员令牌只在浏览器当前页面内存中使用；退出或刷新后重新输入。请通过HTTPS访问，勿把令牌写入小程序或仓库。

启动前设置 `OPS_ADMIN_TOKEN` 与 `OPS_DATA_DIR`。后者默认 `./data/ops`，保存内容草稿、已发布版本、最近20次发布与100条操作日志。使用原子文件替换且只支持单实例；云托管必须挂载持久卷，不能依赖容器临时文件系统，多实例前应迁移数据库。

接口：`GET /api/ops/overview` 返回真实模型配置和目录版本（暂无请求成功率、耗时统计）；`GET /api/ops/content` 返回 `{version,draft,published,history,audit}`；`PUT /api/ops/content` 接收 `{version,entries}` 保存草稿；`POST /api/ops/content/publish` 接收 `{version}` 发布；`POST /api/ops/content/rollback` 接收 `{version,targetVersion}` 回滚至保留的发布版本。并发旧版本返回409。内容固定四个问题ID，标题不超过60字、问题不超过600字，拒绝HTML和控制字符。

`GET /api/content/starters` 无需登录，仅返回已发布 `{version,entries}`。管理后台不能改写模型系统规则或营养数据；目录条目仍由带来源的代码版本发布。当前没有用户照片、档案或原始对话浏览功能，也没有虚构运营统计。
