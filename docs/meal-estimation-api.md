# 餐食估算接口 v1

`POST /api/cases/estimate-meal` 使用原有云托管身份验证、图片大小限制和AI请求限频。请求沿用 `IdentifyRequest`：caseId、revision、text、images、scene（solo/night）。补充回答追加在text中；不支持把整桌食物直接当个人摄入。

响应 version 为 `meal-estimate-v1`。items 项目 type 为 estimate；amountMin/amountMax 与 unit（g/mL）表示原始整份估计范围。estimate 下 energyKcal/proteinG/fatG/carbG 都是这整份的营养范围，**不是每100克**；未知宏量营养素为null。消费比例由客户端记录并确定计算，不修改原始估算。

estimate.source.kind 为 ai/catalog/label；服务器决定来源，模型不允许提供来源ID。只有食物中文名称与目录名称完全相同、单位一致时才校准为catalog；不会拿复合菜中的一种原料替代整道菜。标签依据仅供用户核对，不等于实测。portionSource 与营养来源独立保留。assumptions记录估计依据。顶层energyKcal为服务端求和，representativeKcal为中点；question可为空，最多一个问题及2—4个选项。空items不代表零摄入，客户端应显示重拍或文字记录。

## 配置

- `FANZUI_MEAL_ESTIMATE_ENABLED=true`（默认）：新估算API开关；false返回ESTIMATE_DISABLED，不影响旧饭案读取。
- 未配置独立视觉服务时，使用现有DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL。这只是配置复用，不代表已证明该模型支持看图。
- 可使用独立OpenAI兼容视觉服务：同时配置 `VISION_API_KEY`、`VISION_BASE_URL`、`VISION_MODEL`。需要HTTPS API origin，调用 `/chat/completions`；不携带DeepSeek特有thinking参数。视觉服务由部署者明确选择，不能把密钥发送给另一个未配置的供应商。
- 估算每次上游请求至多25秒（亦受AI_TIMEOUT_SECONDS更短配置限制），仅结构或JSON校验失败可修复一次。上游网络/能力失败不自动重试，返回安全错误并保留客户端草稿。

## 验证与监控

本地测试以模拟模型验证真实图片媒体封装、JSON修复上限、营养范围/单位、来源防伪、标签换算、权限及开关。未使用线上密钥进行实际模型或供应商准确率评测。部署前必须完成真实餐图验证；不能宣称已验证DeepSeek图像能力。

现有OpsMetrics新增估算路由的成功/失败、状态码与耗时；estimateSources只累计ai/catalog/label条目数。统计为当前实例内存聚合，重启清零；不写入食物名、图片、用户身份或模型原始输出。
