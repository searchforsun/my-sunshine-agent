# ReAct 实时轮次 / Context Token / 输入输出 Token 显示 — 设计方案

> 状态：✅ 已实现（待前端人工验收）
> 范围：P1 仅 ReAct 主对话链路（用户已确认）；直连路径（workflow llm/answer 节点、意图路由）延后。
> 对标：Claude Code status line / DeepSeek Harness session event log。

## 1. 背景与目标

前端需要对标 Claude Code / DeepSeek Harness，实时显示：

1. **对话轮次**（turn）；
2. **上下文 token 计数与占用百分比**（context left）；
3. **输入 / 输出 token**（单次调用 + 消息累计）；
4. 刷新页面后上述状态可恢复。

### 1.1 对标结论（调研沉淀）

| 维度 | Claude Code | DeepSeek Harness | 本方案采纳 |
|------|-------------|------------------|------------|
| usage 来源 | 每次 LLM 调用返回的 usage 记账，status line JSON 下发 | `assistant/message` 事件直接携带 usage（输出与记账同行，无单独记录） | **usage 随 SSE 事件下发 + 随消息落库**（DeepSeek 哲学） |
| context 占用口径 | 内部 auto-compact 用 `input+output` 对有效窗口计算 | TokenMeter 按派生历史测压力 | **最近一次调用的 `inputTokens+outputTokens` ÷ contextWindow**（见 §3.3） |
| 教训 | 社区自行估算 token 低估 15–48 个百分点（漏 system prompt / 工具定义 / MCP 元数据；只算输入漏输出） | — | **绝不前端/后端自行估算，只用网关返回的真实 usage** |

### 1.2 术语定义

- **turn（轮次）**：会话内第 N 条用户消息（一轮 = 一条用户消息触发的完整执行）。**由前端计算**（会话消息序列可得），后端不下发——与 Claude Code 一致：token 是后端事实，轮次是前端视图。
- **llmCall**：一条用户消息（一条 assistant 消息生命周期）内第 N 次 LLM 调用（ReAct 下 think/tool/generate 每次模型调用各计一次）。
- **context 占用**：最近一次 LLM 调用后，下一次调用的预估上下文规模。

## 2. 现状与根因

### 2.1 断链根因（llm-gateway）

AgentScope SDK `OpenAIChatModel` 流式请求**已自动携带** `stream_options:{include_usage:true}`（`OpenAIChatModel.java:134-138`），但 gateway 的 `ChatCompletionRequest` 标注 `@JsonIgnoreProperties(ignoreUnknown=true)` 且**无 `stream_options` 字段**——反序列化时被静默丢弃，未转发上游，末 chunk 的 usage 链路因此断裂。响应侧 `NormalizeFilter.normalizeStreamData` 对末 chunk 的 usage 节点原样保留，`ChatCompletionResponse.Usage`（`ChatCompletionResponse.java:55-59`）已存在，**响应侧零改动**。

### 2.2 已有基建（全部复用，无需新建）

| 基建 | 位置 | 用途 |
|------|------|------|
| `ModelCallEndEvent`（携带 `ChatUsage{inputTokens, outputTokens, cachedTokens, time}`） | AgentScope SDK，`ReActAgentRuntime.routeDeltaToBridge`（`ReActAgentRuntime.java:340`）流经处 | usage 采集点 |
| `ChatCompletionResponse.Usage` | `llm-gateway/.../ChatCompletionResponse.java:51-59` | 响应解析（已存在） |
| `model_definition.context_window / max_output_tokens`（MySQL SSOT）+ orchestrator `ModelWindowCache` | 模型注册表 | 百分比分母 |
| `StreamToken` kind 机制 + 前端 `parseSsePayload` 前向兼容（未知 type → ignore） | `StreamToken.java:9-25`、`sseDispatch.ts:140-152` | SSE 协议扩展 |
| BFF 帧级透传（data JSON 不解不重组） | `bff/OrchestratorClient.java:35-47` | **BFF 零改动** |

## 3. 总体设计

### 3.1 数据流

```
AgentScope OpenAIChatModel (stream_options: include_usage ✅ SDK 已带)
  → llm-gateway ChatCompletionRequest 补 stream_options 字段并转发 ①
  → 上游末 chunk usage → NormalizeFilter 原样保留 → SDK 解析为 ChatUsage
  → ReActAgentRuntime.routeDeltaToBridge 捕获 ModelCallEndEvent ②
      → StepEventBridge 发 usage 载荷进 hookQueue（与 reasoning/content 同序 drain）
      → runtime drain 时映射为 StreamToken(KIND_USAGE) ③
  → GenerationJobChunkEmitter.emitSingleMappedChunk 分配 seq
  → GenerationStreamService XADD（Redis Stream，回放/续连天然可用）
  → ChatController SSE → BFF 透传 → 前端 parseSsePayload 新增 usage handler ④
  → chatStore / chatSessions 更新消息 usage 字段 → UsageStatusBar 渲染 ⑤
  → 终态 GenerationJob.persistFinal 携带累计 usage 落 chat_message.usage_json ⑥
      → MessageDto 透出 → 刷新恢复；AuditService payload 附 usage（免 DDL）
```

### 3.2 SSE 事件契约（新增 type=usage）

每次 LLM 调用结束时下发一帧（wire JSON）：

```json
{
  "type": "usage",
  "callSeq": 3,
  "inputTokens": 1234,
  "outputTokens": 856,
  "cachedTokens": 1024,
  "contextTokens": 2090,
  "contextWindowTokens": 128000,
  "contextPercent": 2,
  "messageUsage": {
    "inputTokens": 3100,
    "outputTokens": 2100,
    "llmCalls": 3
  },
  "groups": {
    "system": 1600,
    "rules": 300,
    "skills": 900,
    "tools": 6700,
    "contextLayers": 1200,
    "messages": 6900,
    "other": -300
  }
}
```

- `callSeq`：本条 assistant 消息内第几次 LLM 调用（从 1 起，续跑接续）。
- `contextTokens = inputTokens + outputTokens`（本次调用）；`contextPercent = contextTokens / contextWindowTokens`，分母取 `ModelWindowCache`（模型注册表 SSOT）；**模型不在注册表时 `contextWindowTokens/contextPercent` 置 null**，前端只显示绝对值。
- `messageUsage`：本条消息累计（多次调用求和），前端一处消费、免自行聚合。
- 有 usage 即累计（含以 max-tokens / 异常结束的调用——DeepSeek 教训：失败调用的 usage 也是记账事实）。
- **不含 turn 字段**（前端按用户消息数计算，见 §1.2）。

### 3.3 context 口径（规避 Claude Code 社区踩过的坑）

- 分子 = 最近一次调用的 `inputTokens + outputTokens`。`inputTokens` 由网关按真实请求计费口径返回，**天然包含 system prompt + 工具定义 + 全部历史**，无估算误差；加 `outputTokens` 因其即将成为下一轮上下文。
- `cachedTokens` 含在 `inputTokens` 内（缓存命中同样占用上下文），仅作展示参考，不参与分子换算。
- 禁止任何环节用 jtokkit 估算替代网关 usage 的**总额/百分比/裁剪**口径；`TokenEstimator` 仅继续服务上下文裁剪与分组构成展示（§3.4，仅展示用）。

### 3.4 上下文分组构成（对标 Cursor Context Usage 面板）

状态栏 `ctx 12%` 点击/悬停弹出**上下文构成面板**，按类别分组展示 token 占用（近似值，逐组标 ~；总额仍以网关真实 `inputTokens` 为锚）：

```
上下文已用 12% · ~15.3K / 128K
▓▓▓▓░░░░░░░░░░░░░░░░
 系统提示词        ~1.6K
 工具定义          ~6.7K
 对话消息          ~6.9K
 ──（hover 行内可细分）
```

**分组映射（本项目 6 层 PromptComposer 结构，不是照搬 Cursor 类目）**：

| 组 | 来源 | 估算点 |
|----|------|--------|
| 系统提示词 | `system-prompt`（base-system，ReAct 由 `ReActAgent.sysPrompt` 承载）+ `scope-prompt` | jtokkit 逐条估算 |
| 用户规则 | `PersonalRulesSupport.wrap`（个人规则 soul 层） | 同上 |
| 技能 / 模式 overlay | `resolveSkillOverlay` + `resolveModeOverlay` + `resolveHarnessOverlay` 等（`PromptComposer.java:72-91`） | 同上 |
| 工具定义 | AgentScope Toolkit 注册工具 schema | 工具 JSON schema 逐工具估算 |
| 上下文层 | `ContextMessageBuilder.appendAll`（L1 压缩 / RAG 检索结果 / 工作区注入，`PromptComposer.java:98-112`） | 同上 |
| 对话消息 | AgentScope Memory 中的历史对话（含 reasoning / 工具调用回执） | 逐条估算 |
| 其他（残差） | SDK 内部信封、对齐误差 | `总 inputTokens − Σ 以上各组` |

**实现要点**：

- **静态/半静态组**（系统提示词 / 用户规则 / 技能·模式 / 上下文层）：`PromptComposer.composeReactInputs` 各层拼装时**同步记录每层文本并 jtokkit 估算**，形成 `Map<String, Integer> groupTokens` 随 runtime 传递；**仅复用既有 `TokenEstimator`，不新建分词器**。
- **动态组**（对话消息 / 工具定义）：composer 拿不到 Memory 与 Toolkit，采集点放在 runtime 每次模型调用前——`ProcessingStepMiddleware.onModelCall`（`ProcessingStepMiddleware.java:116`，改入参 messages 的既有挂载点）对入参 messages 中「非 composer 层」的对话历史消息逐条估算；工具定义由 runtime 从 Toolkit 序列化 schema 估算（每轮一次，工具集不变则缓存复用）。
- 数据随 §3.2 usage 帧扩展下发：usage 帧增 `groups` 字段，**每帧携带**（末帧即最新快照，前端取末帧；不追求「仅末帧」，因末帧事前不可判定）：`{ "system": 1600, "rules": 300, "skills": 900, "tools": 6700, "contextLayers": 1200, "messages": 6900, "other": -300 }`。
- **残差组**：`other = inputTokens - Σ估算组`，允许为负（jtokkit 与真实分词器有偏差），前端展示时钳制 ≥0 并归入其他；面板上总额用真实 `inputTokens`，各组标 ~ 明示近似——**这是估算与记账的唯一交界，绝不反向用于裁剪**。
- 面板复用 Naive UI `NPopover`；行内按 `--sun-black` + 边框分区；无解释性文案，仅组名 + token 值。
- 分组构成随 §4.5 落库（`usage_json` 内含 `groups` 快照）→ 刷新恢复。

## 4. 详细设计

### 4.1 llm-gateway：透传 stream_options（改造点 ①）

- `ChatCompletionRequest` 增加 `stream_options` 字段（`Map<String,Object>` 或小 DTO，`@JsonInclude(NON_NULL)`）。
- `OpenAiRequestBodyFactory.build`（`OpenAiRequestBodyFactory.java:33-56`）在流式分支将请求中的 `stream_options` 写入上游 body；请求未携带时不注入（保持对非 AgentScope 调用方零影响）。
- 响应侧无需改动（§2.1）。

### 4.2 orchestrator：采集（改造点 ②③）

- `ReActAgentRuntime.routeDeltaToBridge`（`ReActAgentRuntime.java:340`）新增 `ModelCallEndEvent` 分支：取 `event.getUsage()`，经 `StepEventBridge` 发 usage 载荷进 hookQueue，由 runtime 统一 drain（与 reasoning/content 同序，禁止直灌 SSE）。
- `StreamToken` 新增 `KIND_USAGE = "usage"` 常量、usage 载荷字段与工厂方法（record 加可空字段，不影响既有 kind）。
- runtime 维护**消息级累计器**（inputTokens/outputTokens/llmCalls），每个 `ModelCallEndEvent` 累加并随事件下发 `messageUsage`；续跑（resume）时从消息已落库 `usage_json` 起算，不从 0 重计。
- 边界：仅统计**主 Agent（MAIN）** 的调用。spawn_subagent 子 agent（上下文隔离）与 Planner Worker 的调用不计入主消息 usage——若其事件流经主 bridge，按 bridge 维度过滤；子 agent 卡片内 usage 展示为 P2 可选项。

### 4.3 下发与缓冲（改造点 ③ 续）

- `GenerationFlushScheduler` 新增 `metaUsage(...)` 构造 wire JSON（§3.2 格式）。
- `GenerationJobChunkEmitter.emitSingleMappedChunk`（`GenerationJobChunkEmitter.java:192-206`）新增 usage 分支：分配 seq + XADD，与既有 kind 同锁串行。
- Redis Stream 机制天然支持回放（`readFrom`）与续连（`subscribeToEnd`），断线重连不丢 usage 帧。

### 4.4 前端（改造点 ④⑤）

**数据层**

- `sseDispatch.ts`：`handlers` 新增 `usage` → `ParsedSsePayload` 加 `kind:'usage'` 分支（payload 即 §3.2 结构）。
- `chat.ts` 的 `ChatMessage` 增加 `usage?: MessageUsage`（`{inputTokens, outputTokens, llmCalls, contextTokens?, contextWindowTokens?, groups?}`）。
- `chatSessionSseConsumer.ts` 新增 usage 分支：就地更新 `s.messages` 末条 assistant 的 `usage`。
- `mapApiMessages`（`chatStore.ts:111-142`）映射历史消息 `usage` → 刷新恢复。
- 轮次：`turn = 会话内用户消息数`，由前端计算；进行中的一轮为当前 turn。

**展示层**（遵循 `--sun-black` + 边框分区，禁止解释性文字）

- 新组件 `UsageStatusBar`，挂 `ChatView.vue:1981` `composer-toolbar`（右栏 ModelSelector 左侧）：

```
T3 · ↑ 3.1k ↓ 2.1k · ctx 12%
```

  - `T3`：当前轮次（turn）；`↑/↓`：本轮消息累计输入/输出 token（k 缩写）；`ctx`：context 百分比（无分母时显示 token 绝对值）。
  - 颜色分级：`<60%` 默认色、`60–85%` warn、`>85%` error（复用 Naive UI 主题 token）。
- **上下文构成面板**：点击状态栏 `ctx` 弹出 `NPopover` 分组面板（§3.4 分组：系统提示词 / 用户规则 / 技能·模式 / 工具定义 / 上下文层 / 对话消息 / 其他），组名 + ~token 值，总额用真实 `inputTokens`；数据来自 usage 帧 `groups` 字段。
- assistant 消息尾部 meta 行（时间戳旁）追加小字：`3 calls · ↑3.1k ↓2.1k`（消息级，刷新后仍在）。
- 状态栏数据源：会话末条 assistant 消息的 `usage` + 前端轮次计算；无 assistant 消息时仅显示 `T{N}`。

### 4.5 持久化与恢复（改造点 ⑥）

- `chat_message` 加列 `usage_json JSON NULL`（DDL：`docker/mysql/init/11-sunshine-orchestrator.sql`；线上先 `ALTER TABLE`，与种子 SQL 同步为全量快照一致策略）。
- `ChatMessageEntity` 加字段；`ConversationService.updateMessage`（`ConversationService.java:417-442`）签名加 usageJson；`GenerationJob.persistFinal`（`GenerationJob.java:331-366`）携带 runtime 累计值经 `commitFinal` 落库。
- `ConversationDetailDto.MessageDto`（52-66 行）加 `usage` 字段，`from` 映射。
- 审计：`AuditService`（`AuditService.java:51-66`）payload 增加 `usage` 节点（`chat_audit_log.payload` 为 JSON，**免 DDL**）。

## 5. 排除项（P1 不做）

- 直连路径 usage（workflow llm/answer 节点 `WorkflowLlmStreamSupport`、意图路由 `IntentRouter`）——gateway ① 修好后天然具备条件，P1.5 顺延。
- 会话级跨消息累计、成本估算（需 `model_definition` 价格列）——P2/P3。
- 子 agent / Worker 卡片内 usage 展示——P2 可选。
- 前端任何 token 估算逻辑（原则性禁止，见 §3.3）。

## 6. 验收标准

后端（可用脚本验证）：

1. gateway 收到带 `stream_options` 的流式请求后，上游 body 含该字段；末 chunk usage 透传回 SDK（`ChatUsage` 非空）。
2. ReAct 一轮含 N 次模型调用的对话，SSE 流中出现 N 帧 `type=usage`，`messageUsage.llmCalls = N`，各帧 seq 单调。
3. 终态后 `chat_message.usage_json` 与末帧 `messageUsage` 一致；`chat_audit_log.payload` 含 usage 节点。
4. 断开重连（续连口）不丢 usage 帧（Redis Stream 回放）。
5. 续跑场景累计接续（不从 0 重计）。

前端（**不由 agent 自测，人工按以下步骤验证**）：

1. 发起一轮多步 ReAct 对话（如带工具调用的问题），观察 composer 状态栏：轮次随用户消息递增；每次模型调用结束 `↑/↓/ctx` 更新；`ctx` 百分比与模型注册表窗口一致。
2. 刷新页面：状态栏与消息尾 meta 行数据恢复。
3. 构造长对话使 context 超过 60% / 85%，确认颜色分级生效。
4. 点击状态栏 `ctx` 弹出分组面板：各组 ~token 与真实总额同屏，Σ 组 + 残差 ≈ 总额；刷新后分组快照恢复。
5. 旧版本兼容：确认未知 `usage` 帧在旧前端被忽略（前向兼容）。

## 7. 实施顺序

1. gateway `stream_options` 透传（①，独立可验：SDK `ChatUsage` 非空）。
2. orchestrator 采集 + StreamToken/SSE + 分组估算（②③，`verify` 脚本可直接断言 SSE 帧）。
3. 持久化 + 审计 + DTO（⑥）。
4. 前端数据层 + `UsageStatusBar`（④⑤）。
5. 联调：建议新增 `scripts/verify_usage_stream_live.py`（登录 → 发多步对话 → 断言 SSE usage 帧数与累计 → 断言落库）。
