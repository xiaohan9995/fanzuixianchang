# 餐食估算接口 v1

`POST /api/cases/estimate-meal` 使用原有云托管身份验证、图片大小限制和AI请求限频。请求沿用 `IdentifyRequest`：caseId、revision、text、images、scene（solo/night）。补充回答追加在text中；不支持把整桌食物直接当个人摄入。

响应 version 为 `meal-estimate-v1`，提示词版本为 `meal-estimate-prompt-v3`。items 项目 type 为 estimate；amountMin/amountMax 与 unit（g/mL）表示原始整份估计范围。estimate 下 energyKcal/proteinG/fatG/carbG 都是这整份的营养范围，**不是每100克**；碳水/蛋白质/脂肪必须尽量给出范围，只有完全无法判断时才为null。消费比例由客户端记录并确定计算，不修改原始估算。

v3 提示词要求按肉眼可分辨的食材拆分，而不是只给“麻辣烫一份”：主食、肉/蛋/豆制品、蔬菜、血制品/加工肉、汤底与浮油、另装酱料分别估算。汤底、红油、麻酱等高热量变量必须单列或说明；另装蘸料若是否拌入会显著改变热量，应先通过 question 问清，不默认全部计入。每项先估实际食用重量范围，再给热量和三大营养素，并自检蛋白质4、碳水4、脂肪9千卡/克的大致关系。客户端结果页同时展示整餐热量浮动范围、三大营养素总量和每项份量/营养详情；未知项不按0计算。

estimate.source.kind 为 ai/catalog/label；服务器决定来源，模型不允许提供来源ID。只有食物中文名称与目录名称完全相同、单位一致时才校准为catalog；不会拿复合菜中的一种原料替代整道菜。标签依据仅供用户核对，不等于实测。portionSource 与营养来源独立保留。assumptions记录估计依据。顶层energyKcal为服务端求和，representativeKcal为中点；question可为空，最多一个问题及2—4个选项。空items不代表零摄入，客户端应显示重拍或文字记录。

## 配置

- `FANZUI_MEAL_ESTIMATE_ENABLED=true`（默认）：新估算API开关；false返回ESTIMATE_DISABLED，不影响旧饭案读取。
- 未配置独立视觉服务时，使用现有DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL。这只是配置复用，不代表已证明该模型支持看图。
- 可使用独立OpenAI兼容视觉服务：同时配置 `VISION_API_KEY`、`VISION_BASE_URL`、`VISION_MODEL`。需要HTTPS API origin，调用 `/chat/completions`；视觉服务由部署者明确选择，不能把密钥发送给另一个未配置的供应商。
- 豆包 Seed 2.1 Lite 示例：`VISION_BASE_URL=https://ark.cn-beijing.volces.com/api/v3`、`VISION_MODEL=doubao-seed-2-1-lite-260915`（或方舟推理接入点 ID `ep-...`）、`VISION_THINKING=disabled`。豆包识图支持 OpenAI 风格 `image_url`（含 base64 data URI）和 `response_format: json_object`；`VISION_THINKING` 会作为顶层 `thinking` 参数发送，关闭默认的深度思考以降低延迟。只切换识图/餐食估算，文本调查仍用 `DEEPSEEK_MODEL`。
- 估算每次上游请求至多25秒（亦受AI_TIMEOUT_SECONDS更短配置限制），仅结构或JSON校验失败可修复一次。上游网络/能力失败不自动重试，返回安全错误并保留客户端草稿。
- 语音转写是独立接口 `POST /api/speech/recognize`，复用 DEEPSEEK_API_KEY，但不走聊天/视觉模型；配置 `AI_ASR_ENABLED` 与 `AI_ASR_TIMEOUT_SECONDS`。录音不落盘，接口上限 4 MiB/120 秒，客户端录音上限 60 秒。

## 验证与监控

本地测试以模拟模型验证真实图片媒体封装、JSON修复上限、营养范围/单位、来源防伪、标签换算、权限及开关。未使用线上密钥进行实际模型或供应商准确率评测。部署前必须完成真实餐图验证；不能宣称已验证DeepSeek图像能力。

现有OpsMetrics新增估算路由的成功/失败、状态码与耗时；estimateSources只累计ai/catalog/label条目数。统计为当前实例内存聚合，重启清零；不写入食物名、图片、用户身份或模型原始输出。
