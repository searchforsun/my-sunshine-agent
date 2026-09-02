# 时间线总览行（总耗时 + 整段折叠）

> **阶段**：体验增强（前端）  
> **状态**：🟢 已实施 · [plan](../../plans/2026-07-20-timeline-summary-duration.md)  
> **日期**：2026-07-20  
> **前置**：Timeline V2（`lifecycle` + `summary`）· `OperationStack` / `OperationCard` · 正文穿插 `contentInterleave.ts`  
> **范围**：以 `sunshine-ui` 为主；为刷新兜底可暴露已有 `MessageDto.updatedAt`（不新增 SSE duration 字段）

---

## 1. 定位

在每条 assistant 消息的时间线**顶部**增加一行总览：展示整轮墙钟耗时与状态文案，并可一键展开/折叠下方「实现线」。

| 能力 | 定义 |
|------|------|
| **总耗时** | 墙钟（非整段步骤 `durationMs` 求和） |
| **状态文案** | 正在处理 / 已完成 / 已中断 / 已失败 |
| **整段折叠** | 收起实现步骤与中间穿插正文；只留总览行 + 终稿正文块 |
| **默认态** | 进行中 / 终态均默认折叠；用户手动切换后不再被状态抢走 |

**不替代**：单步 `.op-dur`、单卡 chevron、Plan 抽屉 / DAG 放大。

### 1.1 已锁定决策

| # | 决策 |
|---|------|
| D1 | **前端主导**；不新增 SSE 字段、不落库 `message.durationMs`；可复用已有 `updatedAt` 供刷新 hydrate |
| D2 | 耗时用**整轮客户端墙钟（含正文流式）**：`timelineStartedAt`→`now`/`timelineEndedAt` 单调；刷新优先客户端 stamp / `updatedAt`；无端点时才可回退 step 时钟（A8）；禁止各步 `durationMs` 求和 |
| D3 | 文案：`正在处理` / `已完成` / `已中断` / `已失败` + 时钟 |
| D4 | 时钟格式独立：`42s` / `1m20s`（秒取整）；与单步 `formatDuration`（`1.2s`）分离 |
| D5 | **默认折叠**（含进行中 / 终态）；用户点开后 `userToggled` 覆盖 |
| D6 | 用户点过 → `userToggled` 覆盖，状态变化不再自动改 expand |
| D7 | **折叠**：隐藏全部实现步骤 + **中间穿插** `contentBlocks`；**只显示最后一段正文块**（进行中折叠亦同：末步预览 + 末段正文） |
| D8 | 终稿 SSOT：有 `contentBlocks` 时取**最后一个非空块**（勿用整段 `message.content` / join）；无块再回退 `content`；Plan 仍走 `resolvePlanAnswerText` |
| D9 | 避免与 ChatView 底栏 `msg-md` 双显：折叠终稿在 Stack 内渲染时，底栏继续按 `isContentFullyInterleaved` 隐藏；若未 interleaved，底栏照旧、Stack 折叠态不重复铺同一份 |
| D10 | ReAct / Plan-Workflow / peer-collab / spawn 均经 `OperationStack`，同一总览壳；单卡折叠逻辑不动 |

---

## 2. UI 与交互

### 2.1 总览行

结构对齐既有 `.op-line`：gutter chevron + 主文案 + 可选尾部（无单步 pause）。

```
[▾] 正在处理 1m20s     ← 展开
[▸] 已完成 2m10s       ← 折叠
[▸] 已中断 45s
[▸] 已失败 12s
```

- 点击整行（或 chevron）切换展开
- 进行中主文案可沿用步骤行 shimmer 气质（可选，非必须）
- 视觉：`--sun-black` 底 + 边框分区；禁止灰底 card

### 2.2 展开态

与今日一致：`displaySteps` + 步骤后穿插 `contentRowsAfterStep` + orphan 正文。

### 2.3 折叠态

```
[▸ 已完成 2m10s]
[终稿 markdown 一块]
```

| 显示 | 隐藏 |
|------|------|
| 总览行 | intent / think / tool / tasks / plan DAG / peer / subagent 等全部实现行（终态） |
| **正在处理 + 折叠**：`displaySteps` **最后一条**概要行 + **最后一段** `contentBlock` 正文 | 其余步骤 + 中间穿插 `contentBlocks` |
| **终态 + 折叠**：一份终稿正文（最后非空 contentBlock） | 中间穿插的各段 `contentBlocks` 行 |
| | HITL 确认条随实现行一起隐藏（折叠后无法在时间线内点确认；用户可手动展开） |

**HITL 注**：待确认时若用户手动折叠，确认条不可见——可接受；不做折叠态「浮出 HITL」特例（YAGNI）。

### 2.4 默认 expand 规则

```
if userToggled: 用用户值
else: expanded = false   // 进行中 / completed | interrupted | failed 一律默认折叠
```

`live` 仍由 `useChatTimelineView.isTimelineLive` 提供；`status` 由 `ChatView` 传入 `msg.status`。

---

## 3. 数据与计算

### 3.1 墙钟（含正文输出）

口径：从实现开始到**正文输出结束**的整轮等待；`streaming` 期间单调上涨，终态落 `timelineEndedAt`，刷新不回退。

| 端点 | 取值 |
|------|------|
| **start** | **优先** `timelineStartedAt` / 消息 `createdAt`（客户端墙钟）；皆无再 `min(steps[].startedAt ?? ts)` |
| **end（streaming / 未终态）** | `Date.now()`（200ms tick） |
| **end（completed / interrupted / failed）** | **优先** `timelineEndedAt`（可延后抬高）；刷新 hydrate 用消息 `updatedAt`；再无才 `max(endedAt)` |

**禁止**把服务端 step 时钟与浏览器 `now` 混算（会造成先涨后跌）。正文结束时 `stampTimelineEnded`；API `MessageDto.updatedAt` 供刷新兜底。

### 3.2 时钟格式

新增 `formatElapsedClock(ms: number): string`（建议放 `processingStepsDisplay.ts`）：

- `ms < 0` / `NaN` → `''`
- `< 60_000` → `${Math.floor(ms/1000)}s`（不足 1s 显示 `0s` 或 `<1s`，实现时取 `0s`）
- `≥ 60_000` → `${m}m${s}s`（`s` 为余秒，两位数不必补零：`1m20s` / `2m0s`→可写成 `2m0s` 或 `2m`；**锁定写 `2m0s`** 简单一致）

**禁止**复用 `formatDuration` 改语义。

### 3.3 状态文案映射

| `messageStatus` / live | 前缀 |
|------------------------|------|
| `live === true` 或 `streaming` | `正在处理` |
| `completed` | `已完成` |
| `interrupted` | `已中断` |
| `failed` | `已失败` |
| 缺省且非 live | `已完成`（历史消息兜底） |

完整展示：`{前缀} {clock}`；clock 为空则只显示前缀。

### 3.4 终稿正文

新增（或内联）解析函数，例如 `resolveCollapsedAnswerText(msg)`：

1. Plan：`resolvePlanAnswerText`  
2. 有 `contentBlocks` → **最后一个非空块**（折叠态不展示中间穿插段，也不用整段 `message.content`）  
3. 无块 → `msg.content`（非 Plan drawer leak）  

折叠态用 `StaticMarkdown` 渲染该字符串；**流式进行中若用户手动折叠**：显示当前最后一段正在增长的 text，不恢复穿插。

---

## 4. 组件与接线

### 4.1 改动文件

| 文件 | 改动 |
|------|------|
| `api/processingStepsDisplay.ts` | `formatElapsedClock` + `resolveTimelineElapsedMs` |
| `api/contentInterleave.ts`（或 display） | `resolveCollapsedAnswerText`；必要时导出 `joinedContentBlocks` |
| `components/operation/OperationStack.vue` | 总览行、expand state、折叠/展开分支 |
| `views/ChatView.vue` | 向 Stack 传 `message-status`（及若终稿解析需要：确保 content/blocks 已在 steps 同源 msg 上） |
| `e2e/processing-timeline.spec.ts` | 总览文案、默认折叠、折叠无步骤/无中间穿插、有终稿 |

可选：总览抽成 `TimelineSummaryHeader.vue`；若逻辑 <80 行可留在 Stack 内。

### 4.2 Props

`OperationStack` 新增：

```ts
messageStatus?: 'streaming' | 'interrupted' | 'failed' | 'completed'
```

终稿优先用已有 `contentBlocks` + 建议新增可选 `messageContent?: string`（来自 `msg.content`），避免折叠解析缺 content。

### 4.3 双显守卫

- **展开 + interleaved**：底栏隐藏（现状）  
- **折叠 + interleaved**：Stack 内终稿一块；底栏仍隐藏  
- **折叠 + 非 interleaved**：底栏已有 `msg-md` → Stack **不再**再渲染终稿（只留总览行）  
- **展开 + 非 interleaved**：现状（步骤 + 底栏）

判定复用 `isContentFullyInterleaved`。

---

## 5. 非目标

- 后端 `message.durationMs` / 审计字段  
- 改单步耗时格式或单卡默认展开策略  
- 折叠态单独 HITL 浮层  
- 持久化用户 expand 偏好到 localStorage（会话内 `userToggled` 即可）  
- 对模型输出做截断/摘要

---

## 6. 验收

| # | 场景 | 期望 |
|---|------|------|
| A1 | ReAct 流式 | 顶行「正在处理 XmYs」，**默认折叠**；末步预览 + 末段正文；用户可展开看完整穿插 |
| A2 | ReAct 完成 | 自动折叠；顶行「已完成 …」；可见终稿；无步骤行、无中间穿插段 |
| A3 | 用户中断 | 「已中断 …」，默认折叠 |
| A4 | 失败 | 「已失败 …」，默认折叠 |
| A5 | 完成后手动展开 | 恢复完整实现线 + 穿插；再折叠仍只终稿 |
| A6 | Plan-Workflow 完成 | 同 A2（DAG 一并收起） |
| A7 | 底栏不双显 | interleaved 消息折叠/展开均不出现两份终稿 |
| A8 | 历史消息无时间戳 | 有状态文案；时钟可空，不报错 |

---

## 7. 实现顺序建议

1. 纯函数：`formatElapsedClock` / `resolveTimelineElapsedMs` / `resolveCollapsedAnswerText` + 单测或 e2e 断言  
2. `OperationStack` 总览 + expand 默认规则  
3. 折叠分支渲染 + 双显守卫  
4. `ChatView` 接线  
5. e2e 补齐 A1–A7
