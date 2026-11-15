# 运动估算接口 v1

`POST /api/activity/estimate` 走云托管身份验证与工具类限频（每分钟 120 次），纯文本，不接收图片。请求字段：text（2—400 字运动描述）、minutes（可选，1—720，用户选择的时长）、weightKg（可选，20—350，取自健康档案体重）。

响应 version 为 `activity-met-v1`。**模型只给运动名称、强度与 MET 区间，千卡与合计一律由服务端计算**：`kcal = MET × 3.5 × 体重(kg) / 200 × 分钟`。模型返回的任何 `totalKcal`、`weightKg` 或建议都会被丢弃。

items 每项包含 name、minutes、intensity（低/中/高）、metMin/metMax、kcal（区间中点）、kcalMin/kcalMax。区间来自 MET 区间本身，不是固定百分比；顶层 totalKcal 为各项之和，totalMin/totalMax 为各项区间之和。

用户填写的 minutes 优先：模型只负责按各项原始分钟数把总时长按比例分给每项运动，最后一项吸收取整误差，因此行和恒等于总数。未填 minutes 时取模型给出的时长之和并标注「未精确计时」。

weightSource 为 user 或 default。缺省时按 65 公斤估算，并在 assumptions 里要求用户去健康档案补体重。assumptions 另外说明只算运动本身，不含日常活动、通勤与运动后额外消耗。

空 items 表示没有识别出具体运动，此时 totalKcal 为 null 并给出 hint；**空的 items 不等于零消耗**，客户端应引导用户补充描述或手动填写，而不是记 0。

## 配置

- `FANZUI_ACTIVITY_ESTIMATE_ENABLED=true`（默认）：关闭时返回 ESTIMATE_DISABLED，不影响已记录的运动消耗。
- 复用现有 DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL 的文本通路，不走视觉服务。
- 每次上游请求至多 25 秒（亦受 AI_TIMEOUT_SECONDS 更短配置限制）；仅结构或数值校验失败时追加更严格提示重试一次。上游网络失败返回 MODEL_UNAVAILABLE，客户端保留手填入口。

## 验证与监控

本地测试以模拟模型覆盖：MET 中点求和、区间来自 MET 而非固定百分比、体重来源标记、用户时长优先并按比例分摊、模型自报千卡被丢弃、无法识别时给 hint、MET 上限与倒置、0 或超长时长、超过 6 项、空名称被拒。

OpsMetrics 把该路由计入既有路由表，只统计成功/失败、状态码与耗时，不写入运动描述、体重或用户身份。
