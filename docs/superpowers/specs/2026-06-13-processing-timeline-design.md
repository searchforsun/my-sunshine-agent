# 后端处理事件 — 前端展示方案

> 日期：2026-06-13 | 状态：待评审  
> 前置：阶段 1.5 会话 MVP + 阶段 1.6 Generation 重连已落地  
> 关联：`ChatView.vue`、`ReasoningPanel.vue`、`chatSessions.ts`、`GenerationFlushScheduler.java`

## 背景与目标

当前前端只能展示 **reasoning（思考过程）** 与 **content（正文流式）**，无法让用户感知后端流水线：

- 意图识别（simple / knowledge）
- RAG 向量检索
- Agent 工具调用
- 路径选择（直连 LLM vs ReActAgent）

**目标**：在每条 assistant 消息上方，以**可折叠时间线**展示处理阶段，提升可解释性与等待体验；不干扰现有 Markdown 流式渲染与 Redis 重连。

**非目标（本阶段不做）**：

- 展示完整 ReAct 每轮推理原文（已有 `ReasoningPanel` 覆盖）
- 知识库检索结果全文预览（留给 KnowledgeView）
- 运维级 Trace（SkyWalking 另议）

---

## 已锁定决策

| # | 决策项 | 结论 |
|---|--------|------|
| 1 | 展示位置 | assistant 消息块内，**ProcessingTimeline 在 ReasoningPanel 之上** |
| 2 | 交互模式 | 与 ReasoningPanel 一致：流式中默认展开，完成后可折叠 |
| 3 | 事件来源 | **复用 AgentScope `Event` + `Hook`**，Orchestrator 映射为 `type: step` |
| 4 | Agent 路径 | `StreamOptions.eventTypes(REASONING, TOOL_RESULT, AGENT_RESULT, HINT)` + `ProcessingStepHook` |
| 5 | 直连路径 | `ChatController` 推送 intent / generate 步骤 |
| 6 | 协议兼容 | 旧客户端忽略未知 `type`；新客户端对缺失 step 降级为「生成回答」 |
| 7 | 重连行为 | step 事件写入 Redis Stream，重连 `afterSeq` 后时间线状态可恢复 |
| 8 | 状态 | **已实施**（2026-06-13） |

---

## 方案对比

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| A. 前端启发式 | 根据 loading / reasoning / content 出现顺序推断阶段 | 零后端改动 | 无法展示意图、RAG；knowledge 路径长时间空白 |
| B. 后端 step 事件 | Orchestrator 在关键节点推送结构化 SSE | 准确、可重连、可测试 | 需改 Orchestrator + mock-server |
| **C. 分期（推荐）** | P0 启发式占位 → P1 切换为真实 step 事件 | 快速验证 UI，再接线后端 | 两阶段需避免重复展示 |

**推荐方案 C**：先落地 `ProcessingTimeline` 组件与解析逻辑（P0 用 mock 数据 / mock-server），再接 Orchestrator 真实事件（P1）。

---

## SSE 协议扩展

### 现有事件（保持不变）

| type | 用途 |
|------|------|
| `conversation` | 会话 ID |
| `message` | 消息 ID / status |
| `generation` | 重连 ID |
| `reasoning` | 模型推理流 |
| `content` | 正文流 |

### 新增：`type: step`

```json
{
  "type": "step",
  "id": "intent",
  "phase": "intent",
  "status": "running",
  "label": "识别意图",
  "detail": null,
  "ts": 1718200000000
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 步骤唯一键，同 id 多次推送视为**状态更新** |
| `phase` | string | 是 | `intent` \| `rag` \| `agent` \| `generate` |
| `status` | string | 是 | `pending` \| `running` \| `done` \| `error` \| `skipped` |
| `label` | string | 是 | 用户可见短文案 |
| `detail` | string | null | 副文案，如 `知识库查询`、`命中 3 条` |
| `ts` | number | 否 | 毫秒时间戳，用于排序与耗时展示 |

**标准步骤 id 约定：**

| id | phase | 触发时机 | label 示例 |
|----|-------|----------|------------|
| `intent` | intent | IntentRouter 开始/结束 | 识别意图 → `简单对话` / `知识库查询` |
| `rag` | rag | RagTool.searchKnowledge 开始/结束 | 检索知识库 → `命中 3 条` |
| `agent` | agent | ReActAgent stream 开始/首 token | Agent 推理 |
| `generate` | generate | 正文 content 首 token / 完成 | 生成回答 |

**simple 路径典型序列：**

```
intent(running) → intent(done, detail=simple) → generate(running) → [content...] → generate(done)
```

**knowledge 路径典型序列：**

```
intent(running) → intent(done, detail=knowledge) → rag(running) → rag(done, detail=命中 N 条)
→ agent(running) → [reasoning/content...] → agent(done) → generate(done)
```

`generate` 在 knowledge 路径可与 `agent` 合并展示（MVP 可只显示 agent，避免重复）。

### 后端推送点（已实施）

| 来源 | 事件 |
|------|------|
| `ChatController` 意图分类 | `intent: running/done` |
| `ProcessingStepHook`（AgentScope `PreActing`/`PostActing`） | `rag` / `tool-*` running/done |
| `AgentScopeEventMapper`（`EventType.REASONING/AGENT_RESULT/HINT`） | `agent` / `generate` running/done |
| `LlmGatewayClient` simple 路径 | `generate` running/done（ChatController 注入） |

推送实现：`GenerationFlushScheduler.metaStep()` → `StreamToken.step()` → Redis `appendChunk`。

---

## 前端设计

### 数据模型

```typescript
// sunshine-ui/src/api/processingSteps.ts

export type StepPhase = 'intent' | 'rag' | 'agent' | 'generate'
export type StepStatus = 'pending' | 'running' | 'done' | 'error' | 'skipped'

export interface ProcessingStep {
  id: string
  phase: StepPhase
  status: StepStatus
  label: string
  detail?: string
  ts?: number
}

// ChatMessage 扩展
export interface ChatMessage {
  // ...existing
  steps?: ProcessingStep[]
}
```

### 解析（`chatSessions.ts`）

在 `parseSsePayload` 增加分支：

```typescript
if (obj.type === 'step' && typeof obj.id === 'string') {
  return { kind: 'step', step: normalizeStep(obj) }
}
```

`consumeSseStream` 中：

```typescript
if (parsed.kind === 'step') {
  upsertStep(lastMsg, parsed.step)  // 按 id 合并更新
  onProgress?.(s.id)
  continue
}
```

`upsertStep`：同 `id` 覆盖 `status/label/detail`；保持数组顺序按 `STANDARD_ORDER` 或 `ts` 排序。

### 组件：`ProcessingTimeline.vue`

**布局**（assistant 消息内，自上而下）：

```
┌─ 处理过程 ──────────────── [处理中] ─┐
│ ○ 识别意图          知识库查询    ✓   │
│ ● 检索知识库        命中 3 条    …   │  ← running 脉冲动画
│ ○ Agent 推理                        │
│ ○ 生成回答                          │
└─────────────────────────────────────┘
┌─ 思考过程 ──────────────── [思考中] ─┐  ← 现有 ReasoningPanel
│ ...                                  │
└─────────────────────────────────────┘
┌─ 正文 Markdown 流式区 ───────────────┐
```

**视觉规范**（对齐 `ReasoningPanel`）：

| 元素 | 样式 |
|------|------|
| 容器 | 左边框 3px `--sun-blue`，背景 `rgba(59,130,246,0.06)` |
| running | 蓝色脉冲圆点 + `处理中` badge |
| done | 绿色 ✓ |
| error | 红色 ✗ + detail |
| skipped | 灰色删除线 |
| 完成后 | 默认折叠，仅显示一行摘要：`知识库查询 · 检索 3 条 · 已生成` |

**Props：**

```typescript
defineProps<{
  steps: ProcessingStep[]
  expanded: boolean
  live?: boolean  // 流式中
}>()
```

**状态推导（无 step 事件时的 P0 降级）：**

```typescript
function derivePlaceholderSteps(msg: ChatMessage, loading: boolean): ProcessingStep[] {
  if (msg.steps?.length) return msg.steps
  if (!loading) return []
  return [{ id: 'generate', phase: 'generate', status: 'running', label: '生成回答' }]
}
```

### `ChatView.vue` 集成

```vue
<ProcessingTimeline
  v-if="showTimeline(msg, idx)"
  :steps="msg.steps?.length ? msg.steps : derivePlaceholderSteps(msg, loading)"
  :expanded="isTimelineExpanded(msg, idx)"
  :live="loading && idx === messages.length - 1"
  @toggle="toggleTimeline(msg, idx)"
/>
<ReasoningPanel ... />
<!-- 正文区不变 -->
```

`showTimeline`：`loading` 中或 `msg.steps?.length > 0` 时显示。

展开逻辑与 ReasoningPanel 对称：

- 流式中且无正文：默认展开时间线
- 有 reasoning 无 content：时间线 + reasoning 可同时展开
- 用户手动 toggle 后记住选择（`timelineExpanded` Map）

### 重连与缓存

| 场景 | 行为 |
|------|------|
| Redis 重连 | 重放 step 事件 → `upsertStep` 恢复时间线 |
| localStorage 缓存 | `conversationCache` 可选缓存 `steps`（P2） |
| 历史消息 API | `GET /conversations/:id` 返回 `steps` 字段（P2 落库后） |

---

## 分期实施

### Track P0 — 前端骨架（1～2 天）

| 任务 | 文件 |
|------|------|
| 类型 + upsert 工具 | `api/processingSteps.ts` |
| SSE 解析 step | `api/chatSessions.ts` |
| 时间线组件 | `components/ProcessingTimeline.vue` |
| ChatView 集成 + 降级 | `views/ChatView.vue` |
| mock-server 发 step | `mock-server.mjs` |
| E2E | `e2e/processing-timeline.spec.ts` |

验收：mock 路径下可见完整时间线动画；无 step 时显示「生成回答」占位。

### Track P1 — 后端接线（2～3 天）

| 任务 | 文件 |
|------|------|
| `metaStep()` | `GenerationFlushScheduler.java` |
| 意图/RAG/Agent 埋点 | `ChatController.java`, `RagTool.java` |
| Redis 重连包含 step | `GenerationJob.java`（已走 appendChunk，无需改） |
| 集成测试 | `ChatIntegrationTest.java` |

验收：真实对话 simple/knowledge 两条路径时间线正确；刷新重连后状态一致。

### Track P2 — 持久化（可选）

- Flyway `V3__add_message_steps.sql`
- `ConversationService` 终态写入 `steps` JSON
- `conversations.ts` 映射 + 历史会话回放

---

## 测试计划

| 用例 | 预期 |
|------|------|
| simple 对话 | intent(done,simple) → generate → 正文出现 |
| knowledge 对话 | intent(knowledge) → rag(命中N) → agent → 正文 |
| RAG 无结果 | rag(done, 命中 0 条)，正文仍生成 |
| 中途 Stop | 当前 running step 保持，message status=interrupted |
| 刷新重连 | 时间线从 Redis 重放，不重复、不丢失 |
| 旧 mock 无 step | 降级显示「生成回答」单行 |

---

## 风险与约束

1. **Agent 路径 step 粒度**：ReAct 多轮 tool call MVP 只聚合为一条 `rag` step，不逐轮展示。
2. **非 Redis 模式**：`wrapStream` 路径也需推送 step（续传 resume 可选跳过 intent）。
3. **性能**：step 事件频率低（个位数），不影响 SSE 吞吐。
4. **i18n**：MVP 中文固定文案，后续可配置化。

---

## 附录：mock-server step 示例

```javascript
function stepPayload(id, phase, status, label, detail = null) {
  return JSON.stringify({ type: 'step', id, phase, status, label, detail, ts: Date.now() })
}

// knowledge 场景
yield sse(stepPayload('intent', 'intent', 'running', '识别意图'))
await delay(300)
yield sse(stepPayload('intent', 'intent', 'done', '识别意图', '知识库查询'))
yield sse(stepPayload('rag', 'rag', 'running', '检索知识库'))
await delay(500)
yield sse(stepPayload('rag', 'rag', 'done', '检索知识库', '命中 3 条'))
yield sse(stepPayload('agent', 'agent', 'running', 'Agent 推理'))
// ... reasoning / content chunks
```
