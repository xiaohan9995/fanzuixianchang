---
version: 1
slug: "src-main-resources-static-ops-index-html"
primary_target: "src/main/resources/static/ops/index.html"
related_targets: []
---

# Surface brief — 运营装订台

Target: `src/main/resources/static/ops/index.html`
Mode: **operate**
Related: `src/main/resources/static/ops/ops.css`, `src/main/resources/static/ops/ops.js`

## Scope

单人运营后台的全部五件事：装订台（总览）、散件待办（反馈）、资料提案、案卷编写（草稿/发布/回滚/审计）、服务封条（状态与指标）。移除已下架的「首页 AI 首问」编辑。

## Audience & job

唯一的负责人在碎片时间打开。他要回答的第一个问题不是「有多少数据」，是**「今天有没有事」**。第二个问题是「有事的话，哪一件最急」。处理完要能立刻离开，不留在页面上。

## Constraints

- 原生 HTML/CSS/JS，无构建、无依赖、无 CDN。
- 全部现有 API 与乐观锁语义保持不变。
- 令牌失效、冲突、校验失败都要给「发生了什么 + 我该做什么」。
- 临时开放模式必须显著可见。
- 纯键盘可用，焦点可见，`prefers-reduced-motion` 生效。

## Direction contract

**THESIS** — 这是一台**装订台**：散件（用户反馈）递进来，判断写在袋子上，处理完装进卷宗。它拒绝的后台默认是「四个数字卡 + 下方表格」——那个版面先展示「有多少」，而运营者先要「有没有事」。

**OWN-WORLD** — 材料是两种，不是一种配色：导航与结构是**牛皮纸袋**（`#9C7748`），阅读区是袋里的**白纸**（`#FDFAF3`），墨色 `#2B241C`，印章朱红 `#B23A22` 只做状态与可点。字形三档：楷体（案卷标题与关键数字）、系统中文正文、等宽数字（编号与版本）。去色后仍认得出：一条朱红骑缝章横跨两栏。

**STORY** — 打开即看到「桌上还有 N 份散件」与最久一份放了多久；点一条读原文，在右侧写下判断并处置；处理完那条从桌上消失、进入卷宗。他相信的是：这里没有我猜的数字，只有我还没处理的散件。

**FIRST VIEWPORT** — 左侧 232px 牛皮纸导航（五项，各带待办计数），顶栏 62px 白纸条（日期 + 两个状态签），主体两栏 1.62:1：左栏先给一个 46px 楷体大数「桌上还有 3 份」+ 一行「最久 6 小时」，其下三条散件（编号 / 原文 / 处置按钮三列对齐）；右栏两层纸卡，上层是本卷的骑缝章（草稿与线上两栏，中间一枚印章，异版时裂开），下层是服务封条。主操作「处置」在每条最右，手指一次就到。

**FORM** — 掷出方向「装订」（seed: fcb6d3f34362b5b5），已采纳六个被拒挑战者的纪律：当前可见范围的页头索引、朱红只给可点与真状态、原文与处置用一道竖线分开、正在处理的那份占更大装置、详情只露下一步、编号与时间列固定只有状态列变。

**FINISH** — unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

## Memorable moment

**裂开的骑缝章。** 草稿与线上不一致时，那枚横跨两栏的印章从中间断开、两半各自错开几像素，中间露出一道纸色，下面一行「骑缝章已断开 · 草稿和线上不是同一版」。发布后重新盖合。它把「乐观锁版本号」这个抽象概念变成一眼可读的状态。

## Unresolved

- 移动端（<720px）布版：导航收成顶部横条还是抽屉，待构建时定。
- 案卷 JSON 编辑器：结构化表单还是带校验的文本域。当前倾向文本域 + 实时校验清单（更像校样），但要确认 JSON 有高亮才可用。
