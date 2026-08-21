# 多智能体协作 + Handoff 交接 + 外部智能体市场 - 技术设计

> **⚠️ 已被取代**：本文档内容已合并入 [`2026-07-29-multi-agent-unified-design.md`](../2026-07-29-multi-agent-unified-design.md)。保留仅供历史追溯，请以统一设计文档为准。

> **状态**：**内部「专家内核统一并入 P6」方向经 E5 评审不采纳（2026-07-25）**——P6 peer-collab 保留全栈自研，不迁移官方 Subagent；本文仅 **外部专家 A2A 接入（P6+）** 部分仍可作为未来增量参考

> **日期**：2026-07-24（rev.3 · 2026-07-28 术语重命名+Handoff；rev.2 · 2026-07-27 E5 结论）
> **编号**：阶段四增量（~~内部统一并入 AS2 P6~~【E5 否决】；外部 A2A 独立 P6+；Handoff 交接 P6+）
> **前置**：[4.7.6 spawn_subagent](./2026-07-18-react-spawn-subagent-design.md) · [多专家协作](./2026-07-07-expert-consultation-design.md) · ~~[AS2 P6 peer-collab 正式化](../../plans/archive/2026-07-23-agentscope-2-native-first-redesign.md)~~（E5 不迁移，peer 保留自研） · [A2A Protocol v1.0](https://github.com/a2aproject/A2A)
> **一句话**：术语「专家」->「智能体」、「多专家协作」->「多智能体协作」（用户可见层）；智能体间任务交接（Handoff）引入结构化交接包 + 上下文过滤 + 权限裁剪；外部智能体通过 A2A Agent Card 接入。

---

## 0. 术语重命名映射

用户可见层（前端文案 / 执行模式名 / 文档术语 / 新 Handoff 代码）统一用新词；旧 Expert*/Peer* 代码类名、DB 表名（`expert_*`）、Nacos key（`agent.expert.*` / `agent.peer.*`）保留，避免大范围破坏（代码已有 `AgentRuntime`/`AgentRunRequest`，`Expert->Agent` 会命名冲突）。

| 旧术语（代码层保留） | 新术语（用户可见层） |
|----------------------|----------------------|
| 专家（Expert） | 智能体（Agent） |
| 多专家协作（peer-collab） | 多智能体协作 |
| `ExpertConsultationExecutor` | （类名保留，注释/日志用新词） |
| `expert_definition` 表 / `agent.expert.*` / Catalog `expert.*` `peer.*` | （均保留） |

**前端改动**：`/experts` -> `/agents`；标题"专家管理"->"智能体管理"；Chat `$` 提示"专家"->"智能体"；执行模式下拉"多专家协作"->"多智能体协作"。

---
> **日期**：2026-07-24（2026-07-27 标注 E5 结论）  
> **编号**：阶段四增量（~~内部统一并入 AS2 P6~~【E5 否决】；外部专家 A2A 独立增量 P6+）  
> **前置**：[4.7.6 spawn_subagent](./2026-07-18-react-spawn-subagent-design.md) · [多专家协作](./2026-07-07-expert-consultation-design.md) · ~~[AS2 P6 peer-collab 正式化](../../plans/archive/2026-07-23-agentscope-2-native-first-redesign.md)~~（E5 不迁移，peer 保留自研） · [A2A Protocol v1.0](https://github.com/a2aproject/A2A)  
> **一句话**：专家定义 = 命名的子 Agent 配置。内部专家统一执行内核（消除与 spawn_subagent 平行路径）；外部专家通过 A2A Agent Card 接入，后端适配成与内部专家一致的调用契约，前端 `/experts` 分内部/外部两个 tab。

---

## 1. 背景与问题

### 1.1 现状：两套平行的子 Agent 路径

当前存在两种"子 Agent"机制，定义来源不同、执行内核不同：

| 维度 | 专家（peer-collab） | spawn_subagent |
|------|---------------------|----------------|
| 定义来源 | expert-manager DB 预定义（`systemPrompt` + `skillIds` + `toolsJson`） | 主 LLM 临时写 `prompt`，无 skill，工具同主 |
| 触发 | 路由层 `PEER_COLLAB` 模式 / `$expert-id` | ReAct MAIN 元工具 |
| 是否走 `AgentRuntime.run` | **否**，`ExpertPeerAgentFactory.create()` + `agent.call().block()` | **是**，`AgentRuntime.run(AgentRunRequest.sub)` |
| 执行内核 | 独立 `ReActAgent` + 专用 `ExpertSpeakHook` + `ExpertSpeakStreamer` 旁路 | `ReActAgentRuntime`（统一入口） |
| 发言形态 | 两阶段（gather ReAct + speak 单次流式） | 单阶段 ReAct |
| 取消/Timeline | 自研（专家步无单独取消） | `SpawnRunRegistry` / `SpawnSubagentTimelineBridge` |
| 上下文隔离 | `AssembledContext.forSubAgent()` | 同 |

### 1.2 问题

1. **绕过统一入口**：`ExpertHubEngine.invokeAgent` 虽构造了 `AgentRunRequest`（SUB + forSubAgent + skillId + toolWhitelist + systemOverlay），却**不交给 `AgentRuntime.run`**，而是自己 `new ReActAgent` + `call().block()`。这违反 CLAUDE.md「禁止新增兼容门面或绕过 `AgentRunRequest` 直接调 ReActAgent」。
2. **专家被锁死在单一模式**：专家定义（systemPrompt/skill/tools）本质是"预定义子 Agent 配置"，却只在 peer-collab 可用。ReAct 主 Agent 无法"点名请某专家处理子任务"。
3. **两套 Timeline/取消/HITL 旁路**：专家步用 `ExpertSpeakHook` + `expertSpeakSink`，spawn 用 `SpawnSubagentTimelineBridge` + `SpawnRunRegistry`，维护成本翻倍。

### 1.3 目标

| 目标 | 说明 |
|------|------|
| 统一执行内核（内部专家） | 专家发言走 `AgentRuntime.run(AgentRunRequest.sub)`，消除 `ExpertPeerAgentFactory` / `ExpertSpeakHook` / `ExpertSpeakStreamer` 旁路 |
| ReAct 可调用专家 | `spawn_subagent` 元工具支持 `expertId` 参数，主 Agent 可点名专家当预定义子 Agent |
| 外部专家市场 | 通过 A2A Agent Card 接入外部专家，后端适配成与内部专家一致的 `ExpertCatalogEntry` 契约；前端 `/experts` 分内部/外部 tab |
| 消除平行路径 | Timeline 折叠、取消、审计复用 spawn 路径已有的 `SpawnRunRegistry` / `SpawnSubagentTimelineBridge` |

---

## 2. 核心洞察：专家定义 = 子 Agent 配置

`expert_definition` 表字段与 `AgentRunRequest.sub(...)` 入参一一对应：

| expert_definition 字段 | AgentRunRequest.sub 入参 |
|------------------------|--------------------------|
| `system_prompt` | `systemOverlay` |
| `expert_skill_link.skill_id`（取首个 `primarySkillId()`） | `skillId` |
| `tools_json`（Catalog ID 数组） | `toolWhitelist` |
| （运行时补）`query` | `query` |
| （运行时补）`maxIters` | `maxIters` |

专家本质上就是一份**命名的、可复用的子 Agent 配置**。当前被锁死在 peer-collab 调用路径上，是历史分叉，不是本质差异。

---

## 3. 方案选型

### 方案 A（推荐）：专家统一收口到 `AgentRuntime.run` + spawn_subagent 支持 `expertId`

- 专家侧：`ExpertHubEngine.invokeAgent` 改走 `AgentRuntime.run`，两阶段合并为单阶段 ReAct。
- spawn 侧：元工具入参扩展 `{prompt?, expertId?, label?}`，指定 expertId 时从 `ExpertCatalogService` 取配置填入 `AgentRunRequest.sub`。
- 消除 `ExpertPeerAgentFactory` / `ExpertSpeakHook` / `ExpertSpeakStreamer` 旁路。

### 方案 B：A2A 协议接入外部专家（采纳，作为 P6+ 独立增量）

- 外部专家通过 A2A Agent Card（`.well-known/agent-card.json`）接入，描述身份/能力/endpoint/鉴权。
- 后端新增 `ExternalExpertClient`（A2A client）：按 `tasks/sendSubscribe` 提交任务，收 SSE 流（TaskStatusUpdateEvent / TaskArtifactUpdateEvent），适配成 `StreamToken`。
- 外部专家注册到 `expert-manager`（存 agentCard URL + 鉴权配置），运行时由 `ExpertCatalogService` 统一暴露为 `ExpertCatalogEntry`（`source=EXTERNAL`），对上层透明。
- 内部专家走 `AgentRuntime.run`（in-memory），外部专家走 A2A HTTP/SSE（远程），两者在 `ExpertCatalogEntry` 层统一，调用方按 `source` 分派。

### 方案 C：薄桥，不改专家执行内核

- spawn_subagent 加 `expertId` 分支取专家配置，专家在 peer-collab 仍走老两阶段。
- 最小改动，但留下"专家两套执行路径"的债，违反「禁止兼容旧行为兜底」。

**结论：方案 A（内部统一）+ 方案 B（外部接入）组合。** 内部统一并入 AS2 P6；外部专家 A2A 作为 P6+ 独立增量，不阻塞 P6。内部/外部在 `ExpertCatalogEntry` 层统一契约，执行层按 `source` 分派。

---

## 4. 架构与改动

### 4.1 改动一：专家执行内核统一

`ExpertHubEngine.invokeAgent` 不再自己 `new ReActAgent` + `call().block()` + `ExpertSpeakStreamer.streamSpeak`，改为：

```java
// 伪代码：ExpertHubEngine.invokeAgent 改造后
AgentRunRequest request = buildExpertSubRequest(hubRunId, expert, userQuery, contextBlocks, userId, tenantId, assistantMessageId);
String bridgeId = request.resolveBridgeId();
bindExpertTimelineBridge(bridgeId, expert, pendingEntry, callback);  // 复用 SpawnSubagentTimelineBridge 或 expert 专用薄桥
agentRuntime.run(request)
    .doOnNext(token -> dispatchExpertToken(token, pendingEntry, callback))
    .blockLast(Duration.ofMillis(timeoutMs));
```

**两阶段合并策略**：

| 现状两阶段 | 合并后单阶段 ReAct |
|-----------|-------------------|
| 阶段1 gather：`PromptComposer.composeReactInputs` + `agent.call().block()` + `ExpertSpeakHook` | ReAct 自然产出：专家 systemPrompt + skill overlay 约束"先调工具再给结论"，gather instruction 作为 `injectedBlocks` 注入 |
| 阶段2 speak：`ExpertSpeakStreamer.streamSpeak` + `peer.speak-prompt` 模板 + Gateway 直链 | ReAct 终态正文即专家发言；speak 模板的"基于检索材料发言"约束融入 expert.systemPrompt |

**关键约束**：gather 的检索语义不能丢。合并后靠两层保证：
1. expert.systemPrompt 写明"须先调用工具检索再给结论"（种子专家文案需同步调整）。
2. `peer.gather-instruction` 从阶段1 prompt 变为 `injectedBlocks` 注入，ReAct 在思考时可见。

> **与 AS2 P6 的关系**：P6 本来就要动 `ExpertPeerAgentFactory` / `ExpertHubEngine`（迁 streamEvents + 合并两阶段）。本设计的"专家统一收口到 AgentRuntime.run"是 P6 的增量目标，不是另起阶段。P6 完成后 `ExpertPeerAgentFactory` / `ExpertSpeakHook` 删除，专家发言走 `ReActAgentRuntime`（届时已迁 HarnessAgent + streamEvents）。

### 4.2 改动二：spawn_subagent 元工具支持 `expertId`

`SpawnSubagentTool.spawnSubagent` 入参从 `{prompt, label?}` 扩展为 `{prompt?, expertId?, label?}`：

```java
@Tool(name = NAME, description = "...")
public String spawnSubagent(
        @ToolParam(name = "prompt", description = "给子 Agent 的完整任务说明（expertId 为空时必填）") String prompt,
        @ToolParam(name = "expertId", description = "预定义专家 ID（可选）；指定后使用专家的 systemPrompt/skill/tools") String expertId,
        @ToolParam(name = "label", description = "时间线卡片短标题（可选）") String label) {
    ...
    ExpertCatalogEntry expert = resolveExpert(expertId);  // null 则走现有逻辑
    AgentRunRequest request = expert != null
            ? buildExpertSubRequest(expert, prompt, audit, messageId)
            : buildPlainSubRequest(prompt, audit, messageId);  // 现有逻辑
    ...
}
```

| 参数组合 | 行为 |
|----------|------|
| `prompt` 有，`expertId` 空 | 现有逻辑：临时子 Agent，`mode-overlay.subagent`，无 skill |
| `expertId` 有，`prompt` 空 | 预定义专家子 Agent：systemPrompt/skill/tools 来自 expert，query = 主 Agent 当前任务上下文 |
| 两者都有 | 专家配置 + 主写的 prompt 叠加（prompt 作为 query，expert 配置覆盖 overlay/skill/tools） |
| 两者都空 | 报错 |

**expertId 解析**：从 `ExpertCatalogService.find(expertId)` 取 `ExpertCatalogEntry`，填入：
- `systemOverlay = expert.systemPrompt()`（覆盖 `mode-overlay.subagent`）
- `skillId = expert.primarySkillId()`
- `toolWhitelist = ExpertToolsJson.parse(expert.toolsJson())`
- `maxIters` = 专家专用配置（默认 2，对齐现有 peer-collab）
- `query = prompt`（主 LLM 写的任务说明）

### 4.3 改动三：Timeline / 取消复用

| 组件 | peer-collab 专家步（改造后） | spawn_subagent |
|------|-----------------------------|----------------|
| 主时间线卡 | `expert-{expertId}-s{seq}`（保留现有 phase=expert） | `subagent-{runId}` |
| 折叠 Bridge | expert 专用薄桥（或复用 `SpawnSubagentTimelineBridge`） | `SpawnSubagentTimelineBridge` |
| 取消 | peer-collab 整体取消（现状不变） | `SpawnRunRegistry` 单独取消 |
| HITL | 复用 SUB `bindHitlBridge` | 同 |

peer-collab 专家步的 Timeline 形态（`expert-{id}-s{seq}` + `step_delta(result)`）**不变**，只是驱动源从 `ExpertSpeakStreamer` 变为 `AgentRuntime.run` 的 StreamToken。专家步的"专家名 + 一行摘要"由 Catalog `timeline.steps.expert` 驱动，前端不改。

ReAct 中 spawn_subagent 指定 expertId 时，主卡仍为 `subagent-{runId}`，但 `metadata` 增加 `expertId` / `expertName`，前端卡片可展示专家名作为 label（缺省仍"子任务"）。

### 4.4 组件清单

| 组件 | 改动 |
|------|------|
| `ExpertHubEngine` | `invokeAgent` 改走 `AgentRuntime.run`；删两阶段；`createAgent` 删（不再 `new ReActAgent`） |
| `ExpertPeerAgentFactory` | **删除**（专家不再独立建 ReActAgent） |
| `ExpertSpeakHook` | **删除**（专家步 Timeline 由 `AgentRuntime.run` StreamToken 驱动） |
| `ExpertSpeakStreamer` | **删除**（speak 阶段合并进 ReAct 终态正文） |
| `SpawnSubagentTool` | 入参加 `expertId`；新增 `resolveExpert` / `buildExpertSubRequest` |
| `ExpertCatalogService` | 无改动（已有 `find(id)`） |
| `ExpertHubEngine.buildPeerRequest` | 保留，但改为产出标准 `AgentRunRequest.sub(...)`（去掉直接 `new AgentRunRequest` 构造） |
| 前端 `SubagentCard` | `metadata.expertId` 存在时展示专家 displayName 作为 label |

---

## 5. 调用契约

### 5.1 peer-collab 路径（改造后）

```
ExpertConsultationExecutor
  └─ ExpertHubEngine.run(experts, query, sessionMax, ...)
       per expert per round:
         invokeAgent:
           └─ AgentRuntime.run(AgentRunRequest.sub(
                forSubAgent(), query, gatherInstruction+contextBlocks,
                userId, tenantId, msgId,
                expert.primarySkillId(), expert.toolWhitelist,
                expert.systemPrompt(), maxIters=2))
                ├─ ReActAgentRuntime（AS2 P6 后为 HarnessAgent + streamEvents）
                ├─ 子 think/tool 经 bridge 折叠进 expert 步 subSteps
                └─ 终态正文 -> step_delta(result) 流式
       └─ ConsultationSynthesizer.synthesize（不变）
```

### 5.2 ReAct spawn_subagent 路径（扩展后）

```
主 ReAct (MAIN)
  └─ tool_call: spawn_subagent({ prompt?, expertId?, label? })
        ├─ expertId 为空: 现有逻辑（mode-overlay.subagent，无 skill）
        └─ expertId 非空: ExpertCatalogService.find(expertId)
              -> AgentRunRequest.sub(... expert.systemPrompt / skillId / tools ...)
              -> AgentRuntime.run(request)
              -> 终态文本回主 ReAct 作为 tool result
```

### 5.3 AgentRunRequest.sub 字段映射（专家子 Agent）

| 字段 | 来源 |
|------|------|
| `role` | `SUB` |
| `memory` | `AssembledContext.forSubAgent()` |
| `query` | peer-collab: userQuery + gather instruction；spawn: 主写的 prompt |
| `injectedBlocks` | peer-collab: contextBlocks（前序专家发言 transcript）；spawn: `List.of()` |
| `skillId` | `expert.primarySkillId()` |
| `toolWhitelist` | `ExpertToolsJson.parse(expert.toolsJson())` |
| `systemOverlay` | `expert.systemPrompt()` |
| `maxIters` | 专家专用配置（默认 2） |
| `timeline` | `SUB_COMPRESSED` |
| `conversationId` | peer-collab: null；spawn: 主会话（沙箱复用） |

---

## 6. 外部专家市场（A2A 接入）

### 6.1 设计原则：契约统一，执行分派

外部专家与内部专家在 `ExpertCatalogEntry` 层统一，对 `ExpertConsultationExecutor` / `SpawnSubagentTool` 透明。执行层按 `source` 字段分派：

| source | 执行路径 | 通信 |
|--------|----------|------|
| `INTERNAL` | `AgentRuntime.run(AgentRunRequest.sub)` | 同进程 in-memory |
| `EXTERNAL` | `ExternalExpertClient.invoke(query, context)` -> A2A `tasks/sendSubscribe` | HTTP + SSE |

调用方（peer-collab HubEngine / ReAct SpawnSubagentTool）只依赖 `ExpertCatalogEntry`，不感知 source。

### 6.2 A2A Agent Card 接入

#### 6.2.1 Agent Card（外部专家自描述）

遵循 A2A v1.0，外部专家服务在 `/.well-known/agent-card.json` 发布 Agent Card：

```json
{
  "name": "外部法务专家",
  "description": "提供合同审查与法律风险评估",
  "version": "1.0.0",
  "supportedInterfaces": [{
    "url": "https://external-legal.example.com/a2a",
    "protocolBinding": "https",
    "protocolVersion": "1.0"
  }],
  "capabilities": { "streaming": true, "pushNotifications": false },
  "defaultInputModes": ["text/plain"],
  "defaultOutputModes": ["text/plain"],
  "skills": [{ "id": "contract-review", "name": "合同审查", "description": "..." }],
  "securitySchemes": { "bearer": { "type": "http", "scheme": "bearer" } }
}
```

Sunshine 侧不实现 A2A Server，只实现 **A2A Client**（消费外部 Agent Card + 提交 task）。

#### 6.2.2 外部专家注册（expert-manager）

`expert_definition` 表扩展，支持外部专家：

| 新增字段 | 说明 |
|----------|------|
| `source` | `INTERNAL`（默认）/ `EXTERNAL` |
| `agent_card_url` | 外部专家 Agent Card URL（`source=EXTERNAL` 时必填） |
| `auth_config_json` | 鉴权配置 JSON（bearer token / apikey 引用 Nacos 密钥） |
| `endpoint_override` | 可选；覆盖 Agent Card 内 `supportedInterfaces[0].url`（内网代理场景） |

注册流程：admin 在 `/experts` 外部 tab 填入 agentCard URL -> expert-manager 拉取 Agent Card -> 回填 `display_name` / `description` / `tags`（可编辑）-> 存库。`system_prompt` / `tools_json` / `skillIds` 对外部专家**留空**（由远端 Agent 自治，不由本地 overlay 约束）。

#### 6.2.3 ExpertCatalogEntry 扩展

```java
public record ExpertCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemPrompt,       // EXTERNAL 时 null
        List<String> skillIds,     // EXTERNAL 时空
        List<String> tags,
        String toolsJson,          // EXTERNAL 时空（远端自治）
        boolean enabled,
        ExpertSource source,       // 新增：INTERNAL / EXTERNAL
        String agentCardUrl,       // 新增：EXTERNAL 时非空
        String endpointOverride    // 新增：可选
) {
    public enum ExpertSource { INTERNAL, EXTERNAL }
}
```

### 6.3 ExternalExpertClient（A2A Client）

新增 `orchestrator/.../expert/ExternalExpertClient.java`：

```java
@Component
public class ExternalExpertClient {
    private final WebClient webClient;
    private final PromptCatalogHolder catalogHolder;

    /** 提交 task + 收 SSE 流，适配成 StreamToken */
    public Flux<StreamToken> invoke(ExpertCatalogEntry expert, String query, List<String> contextBlocks) {
        String endpoint = resolveEndpoint(expert);
        String authHeader = resolveAuth(expert);
        Map<String, Object> payload = Map.of(
            "message", Map.of("role", "user", "parts", List.of(Map.of("text", composeA2aMessage(query, contextBlocks)))),
            "acceptedOutputModes", List.of("text/plain"));
        return webClient.post().uri(endpoint + "/tasks/sendSubscribe")
                .header("Authorization", authHeader)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)  // SSE data: lines
                .flatMap(line -> mapA2aEvent(line, expert));
    }
}
```

#### A2A 事件 -> StreamToken 映射

| A2A 事件 | StreamToken |
|----------|-------------|
| `TaskStatusUpdateEvent`（state=WORKING） | `step`（active 文案，复用 `timeline.steps.expert`） |
| `TaskArtifactUpdateEvent`（parts[].text） | `content`（正文 delta，与内部专家发言同通路） |
| `TaskStatusUpdateEvent`（state=INPUT_REQUIRED） | `step`（HITL 待确认） |
| `TaskStatusUpdateEvent`（state=COMPLETED） | 终态 `step`（after） |
| `TaskStatusUpdateEvent`（state=FAILED/CANCELED/REJECTED） | 终态 `step`（fail/cancel） |

外部专家的"发言"= A2A artifact 的 text parts 流式拼接，与内部专家 ReAct 终态正文在 Timeline 上表现一致（`step_delta(result)`）。

### 6.4 执行分派：ExpertExecutorRouter

新增 `ExpertExecutorRouter`（或扩展 `ExpertHubEngine`），按 `source` 分派：

```java
Flux<StreamToken> invokeExpert(ExpertCatalogEntry expert, String query, List<String> contextBlocks, ...) {
    return switch (expert.source()) {
        case INTERNAL -> agentRuntime.run(buildInternalSubRequest(expert, query, contextBlocks, ...));
        case EXTERNAL -> externalExpertClient.invoke(expert, query, contextBlocks);
    };
}
```

- peer-collab `ExpertHubEngine.invokeAgent` 改调 `expertExecutorRouter.invokeExpert`（不再直接 `AgentRuntime.run`）。
- ReAct `SpawnSubagentTool` 指定 `expertId` 时同样走 `expertExecutorRouter.invokeExpert`，结果作为 tool result 回主。

### 6.5 前端 `/experts` 双 tab

| Tab | 内容 |
|-----|------|
| 内部专家 | 现有 CRUD（systemPrompt / skill / tools 编辑） |
| 外部专家 | 列表（name/description/tags/状态）+ 新增：填 agentCard URL -> 拉取预填 -> 编辑展示信息 + 鉴权配置 |

外部专家卡片标记「外部」badge（icon + 来源域名）。点击进入详情只读展示 Agent Card 关键信息（endpoint / capabilities / skills），不可编辑 systemPrompt（远端自治）。

Chat `$` 补全和 ReAct spawn `expertId` 两个 tab 的专家**统一列表**（按 enabled 过滤），前端补全时展示 source badge 区分。

---

## 7. Catalog / 提示词

| Catalog id | 改动 |
|------------|------|
| `peer.gather-instruction` | 从阶段1 prompt 变为 `injectedBlocks`（注入 ReAct 思考上下文，不删） |
| `peer.speak-prompt` | **废弃**（speak 阶段合并进 ReAct 终态，模板约束融入 expert.systemPrompt） |
| `mode-overlay.subagent` | 不改；指定 expertId 时不生效（被 expert.systemPrompt 覆盖） |
| `expert.*` 种子 systemPrompt | 需同步调整：写明"须先调用工具检索再给结论"，吸收原 speak-prompt 的"基于检索材料发言"约束 |
| `timeline.steps.expert` | 不改（peer-collab 专家步文案 SSOT） |
| `timeline.steps.subagent` | 不改；spawn 指定 expertId 时 label 取专家 displayName |
| `react.subagent.cancel-result` | 不改 |

> 提示词 SSOT 仍在 prompt-manager Catalog，禁止 Java 硬编码。

---

## 8. 边界与非目标

**做**

- 术语重命名：用户可见层「专家」->「智能体」、「多专家协作」->「多智能体协作」
- 内部专家执行内核统一到 `AgentRuntime.run`
- spawn_subagent 支持 `expertId` 调用预定义专家（内部/外部均可）
- 删除 `ExpertPeerAgentFactory` / `ExpertSpeakHook` / `ExpertSpeakStreamer`
- peer-collab 行为不退化（反应式选人、轮次协调、Synthesizer）
- 外部专家通过 A2A Agent Card 接入，`ExpertCatalogEntry` 统一契约 + 按 `source` 分派执行
- 前端 `/experts` 双 tab（内部/外部）；外部专家 agentCard URL 注册 + 预填
- Handoff 交接包：结构化 `HandoffEnvelope` 替代裸 `contextBlocks`；上下文过滤 + 权限裁剪 + 敏感信息脱敏
- 修复安全缺口：智能体层 HITL 动态开关 / HITL 身份校验 / 工具 output 脱敏 / 审计查询鉴权 / transcript 脱敏

**不做**

- Sunshine 不实现 A2A Server（只做 A2A Client 消费外部专家）
- 外部专家的本地 systemPrompt / skill / tools overlay（远端 Agent 自治）
- 外部专家 A2A push notifications（仅 streaming 模式）
- 专家在 Plan/Workflow agent 节点中的直接引用（节点已有 `params.skill` / `params.systemOverlay`，可后续增量）
- 专家嵌套调用（SUB 仍禁止再 spawn，与 spawn_subagent 一致）
- 全量代码重命名 Expert->Agent（仅用户可见层改词，代码层保留）

---

## 9. 风险与对策

| 风险 | 对策 |
|------|------|
| gather+speak 合并后专家发言质量下降（不再"先检索再发言"） | expert.systemPrompt + gather-instruction injectedBlock 双重约束；种子专家文案同步调整；Live 对比前后发言质量 |
| peer-collab Timeline 形态变化导致前端回归 | 专家步 `expert-{id}-s{seq}` + `step_delta(result)` 形态不变，驱动源从 streamSpeak 变为 AgentRuntime StreamToken，前端无感 |
| spawn_subagent expertId 与 `$` 路由冲突 | 不冲突：`$` 是路由层 L0 硬绑定（进 PEER_COLLAB），expertId 是 ReAct 元工具内主 LLM 主动点名（进 SUB），两者正交 |
| 专家 tools_json 过时（Catalog ID 漂移） | 现有 `ExpertToolsJson.parse` + `intersectEnabledPool` 求交已处理；`["*"]` 哨兵走全量 |
| AS2 P6 迁移期间两阶段代码并存 | 本设计并入 P6，P6 出口门禁含 `verify_peer_collab_live` + `verify_expert_consultation_live`，合入即删旧实现 |
| 外部专家网络不可达/超时 | `ExternalExpertClient` 设超时 + 重试 + 降级（返回错误 tool result，主 Agent 可改用内部专家）；peer-collab 中某外部专家失败不影响其他专家发言 |
| 外部专家鉴权泄露 | `auth_config_json` 只存密钥引用（如 `nacos:expert.auth.legal`），实际 token 从 Nacos 加密配置读取，不落库明文 |
| 外部专家返回非 text artifact | A2A client 只解析 `text/plain` parts，非 text part 跳过并 warn；后续如需多模态再扩展 |
| 外部专家与内部专家 Timeline 不一致 | `ExternalExpertClient` 产出相同 `StreamToken`（step + content），Timeline 形态对前端透明 |

---

## 9.5 Handoff 交接原则（多智能体协作核心）

### 9.5.1 问题：现状是全量历史裸传

当前多智能体协作（peer-collab）中，智能体间上下文经 `contextBlocks` **全量累积传递**（`ExpertHubEngine.java:95-152`）：

```
contextBlocks = [用户问题, 【智能体A】完整发言, 【智能体B】完整发言, ...]
```

无摘要、无截断、无权限裁剪。spawn_subagent 路径虽靠 `forSubAgent()` 硬隔离，但主 Agent 写的 `prompt` 也是裸文本，无结构化。这与 Handoff 原则要求的"受控转派 + 上下文过滤"相悖。

### 9.5.2 交接包（Handoff Envelope）结构化

引入 `HandoffEnvelope`（固定字段），替代裸 `contextBlocks` 累积：

```java
public record HandoffEnvelope(
    String objective,              // 目标：本轮交接要解决什么
    List<ConfirmedFact> confirmedFacts,    // 已确认事实（带来源标记）
    List<String> evidenceRefs,     // 证据链接（工具结果摘要/文档引用）
    List<AttemptedAction> attemptedActions, // 已尝试动作 + 失败原因
    List<String> openQuestions,    // 未解决问题
    List<String> risks,            // 风险点
    String nextStepSuggestion,     // 下一步建议
    String recipientAgentId,       // 接手智能体
    List<String> allowedTools,     // 权限边界：可调用工具
    String expectedOutput          // 期望输出格式
) {
    public record ConfirmedFact(String fact, String source, boolean verified) {}
    public record AttemptedAction(String action, String result, String failureReason) {}
}
```

### 9.5.3 三类不可乱传信息的处理

| 类别 | 风险 | Handoff 处理 |
|------|------|-------------|
| **敏感信息** | userId/tenantId/支付/内部工具返回数据 -> 隐形越权 | 交接包只含 `objective` + `confirmedFacts`（已脱敏）；userId/tenantId **不进** contextBlocks（现状已隔离，`AssembledContext.forSubAgent()`=empty）；工具结果摘要进 `evidenceRefs` 前经 `DesensitizeClient.scrub` |
| **噪声/错误假设** | 模型试错、工具失败、被用户否定的内容带偏新智能体 | `attemptedActions` 标明失败原因；`confirmedFacts` 区分 `verified=true/false`；完整历史保留在后台 `peer_run.transcript_json` trace，**不默认传递**；接收方可按需查询 trace |
| **责任边界** | 交接后谁负责最终回答、谁能调工具、谁能要求补充信息 | `recipientAgentId` 明确接手者；`allowedTools` 裁剪权限（按接收方 `toolsJson` 白名单求交）；Synthesizer 仍是最终正文负责方（不变） |

### 9.5.4 交接流程

```
智能体A 发言完成
  └─ HandoffEnvelopeBuilder.build(transcript, expertA, expertB)
       ├─ 提取 confirmedFacts（从 A 发言中 LLM 抽取或规则标记）
       ├─ 裁剪 allowedTools = A.toolsJson ∩ B.toolsJson（接收方权限）
       ├─ 脱敏 evidenceRefs（DesensitizeClient.scrub）
       └─ 产出 HandoffEnvelope
  └─ 传递给智能体B：envelope 序列化为结构化文本注入 injectedBlocks
       （替代现有 PeerMsgSupport.formatTranscriptBlock 裸文本）
  └─ 完整 transcript 仍落 peer_run.transcript_json（后台 trace）
```

### 9.5.5 权限不足处理

接收方智能体 `toolsJson` 白名单不包含交接包 `allowedTools` 中的某工具时：
- **拒绝接手**该子任务，返回"权限不足：需 X 工具但未授权"
- 或**请求重新授权**：向 HubEngine 发信号，由编排层决定是否升级权限或换人
- **禁止**拿着被裁剪的信息猜测答案

### 9.5.6 质量验证指标

| 指标 | 定义 | 采集方式 |
|------|------|----------|
| 重复提问率 | 接收方重新问了已被前序智能体回答的问题 | transcript 对比 LLM 判定（离线） |
| 错误继承率 | 接收方基于未确认的猜测继续推理 | `confirmedFacts.verified=false` 被引用计数 |
| 敏感字段泄露率 | 交接包含 userId/tenantId/原始支付数据 | 自动化扫描 envelope 内容 |

### 9.5.7 现有安全缺口的修复（Handoff 落地时一并修）

探索发现以下缺口与 Handoff 原则直接相关，应在 Handoff 落地时修复：

| 缺口 | 现状 | 修复 |
|------|------|------|
| 智能体层 HITL 被关闭 | `ExpertHubEngine.bindHitlBridge(..., false)` | 按智能体 `toolsJson` 是否含写工具动态决定 HITL 开关 |
| HITL 确认无身份校验 | `confirmTool` 仅凭 token，不比对发起用户 | `HitlTokenRegistry` 注册时存发起 userId，confirm 时校验 |
| 工具 output 未脱敏 | `ToolAuditService` 只脱敏 params，output 仅截断 240 字符 | output 也走 `DesensitizeClient.scrub` |
| 审计查询无鉴权 | `AuditController` 三接口不校验归属 | 按 conversationId/userId 归属校验 |
| transcript 全文不脱敏 | `peer_run.transcript_json` 含 userQuery 原文 | 落库前对 content 脱敏 |

---

## 10. 检查门

### 10.1 peer-collab 不退化（并入 AS2 P6 出口门）

| # | 场景 | 期望 |
|---|------|------|
| E1 | `$policy-expert $finance-expert 分析差旅报销合规性` | `expert-convene` + ≥2 个 `expert-*` 步 + Synthesizer 正文；无 `generate` 步 |
| E2 | 专家发言含工具调用 | 专家步 `subSteps` 可见 think/tool；主行一行摘要 |
| E3 | 反应式选人 | 第 2 轮起仅部分专家发言；`evaluateContinue` 正常 |
| E4 | Synthesizer Markdown 闭合 | `**` 不丢（TD-076） |

Live：`verify_peer_collab_live.py` + `verify_expert_consultation_live.py`（断言不变）

### 10.2 ReAct 调用专家（新增）

| # | 场景 | 期望 |
|---|------|------|
| R1 | ReAct 主 Agent 调 `spawn_subagent(expertId="policy-expert", prompt="检索差旅住宿标准并返回要点")` | 主卡 `subagent-{runId}` + label=制度专家；抽屉 `spawnPrompt` + `subSteps`；子有 think/tool；终态文本回主 |
| R2 | `spawn_subagent(prompt="...")` 无 expertId | 现有行为不变（`mode-overlay.subagent`，无 skill） |
| R3 | `expertId` 不存在 | 报错进 tool result；无子卡 |
| R4 | 专家工具白名单生效 | 专家子 Agent 仅可调用 `tools_json` 内工具 + `search_knowledge` + sandbox |
| R5 | 专家子 Agent HITL | 抽屉内确认 -> 续跑；主卡 `待确认`->`运行中` |

Live：新增 `scripts/verify_expert_subagent_live.py`（或扩 `verify_spawn_subagent_live.py --suite expert`）

### 10.3 外部专家 A2A 接入（新增）

| # | 场景 | 期望 |
|---|------|------|
| X1 | `/experts` 外部 tab 新增：填 agentCard URL | 拉取 Agent Card 预填 name/description/tags；存库 source=EXTERNAL |
| X2 | `$external-legal 分析这份合同风险` | `expert-convene` + `expert-external-legal-s1` 步；发言经 A2A artifact 流式；Synthesizer 汇总 |
| X3 | ReAct `spawn_subagent(expertId="external-legal", prompt="审查条款3")` | 主卡 subagent + label=外部法务专家；A2A task 流式回 tool result |
| X4 | 外部专家超时/不可达 | tool result 含错误；peer-collab 该专家步标失败，其他专家不受影响 |
| X5 | 外部专家 INPUT_REQUIRED（HITL） | 专家步 `待确认`；用户确认后续跑（A2A `tasks/send` 续传） |
| X6 | 内部+外部专家混合 `$policy-expert $external-legal` | 两专家均发言；Timeline 分别标 INTERNAL/EXTERNAL badge |

Live：新增 `scripts/verify_external_expert_live.py`（需 mock A2A server 或真实外部专家）

### 10.4 Handoff 交接（新增）

| # | 场景 | 期望 |
|---|------|------|
| H1 | `$A $B 协作`：A 发言后 B 接手 | B 收到 `HandoffEnvelope`（非裸 transcript）；B 的 `injectedBlocks` 含结构化字段 |
| H2 | 交接包含敏感字段 | envelope 无 userId/tenantId/原始支付数据；`evidenceRefs` 已脱敏 |
| H3 | A 发言含错误猜测 | `confirmedFacts` 标 `verified=false`；B 不将其当事实继承 |
| H4 | B 权限不足（缺 A 用的写工具） | B 拒绝接手或请求重新授权；不猜测 |
| H5 | 完整历史可追溯 | `peer_run.transcript_json` 含完整 trace；B 可按需查询 |
| H6 | 智能体层写工具 HITL | `toolsJson` 含写工具时 HITL 自动开；确认需校验发起用户身份 |
| H7 | 工具 output 脱敏 | 审计记录的 output 经 `DesensitizeClient.scrub` |
| H8 | 审计查询鉴权 | `/api/audit/*` 校验 conversationId/userId 归属 |

Live：新增 `scripts/verify_handoff_live.py`（H1-H8）

---

## 11. 实施衔接

本设计分两部分实施：

### 11.1 内部专家统一（并入 AS2 P6）

| AS2 P6 任务 | 本设计增量 |
|-------------|-----------|
| P6-1: ExpertPeerAgentFactory 迁 HarnessAgent + streamEvents | 不再"迁"，而是**删除**（专家走 `AgentRuntime.run`） |
| P6-1: ExpertHubEngine 两阶段合并 | 两阶段合并 + gather-instruction 转 injectedBlock + speak-prompt 废弃 |
| P6-1: 删 `ExpertSpeakHook` | 同步删 `ExpertSpeakStreamer` |
| （新增） | `SpawnSubagentTool` 入参加 `expertId` + `resolveExpert` |
| （新增） | 种子专家 systemPrompt 文案调整（吸收 speak 约束） |
| P6-2: Live + 回滚 | 补 E1-E4 + R1-R5 检查门 |

实施时先做 9.1（peer-collab 不退化），再做 9.2（ReAct 调专家），确保统一内核稳定后再扩展元工具。

### 11.2 外部专家 A2A 接入（P6+ 独立增量，不阻塞 P6）

| 任务 | 说明 |
|------|------|
| expert-manager DB 扩展 | `expert_definition` 加 `source` / `agent_card_url` / `auth_config_json` / `endpoint_override` |
| expert-manager API | 外部专家 CRUD + Agent Card 拉取预填接口 |
| `ExpertCatalogEntry` 扩展 | 加 `source` / `agentCardUrl` / `endpointOverride`（orchestrator + expert-manager 两份 DTO 同步） |
| `ExternalExpertClient` | A2A Client：`tasks/sendSubscribe` + SSE 事件 -> StreamToken |
| `ExpertExecutorRouter` | 按 source 分派 INTERNAL/EXTERNAL |
| 前端 `/experts` 双 tab | 内部 tab 不变；外部 tab 新增（agentCard URL 注册 + 详情只读 + source badge） |
| Nacos 鉴权配置 | `expert.auth.{id}` 加密存外部专家 token |
| Live | X1-X6 检查门 + mock A2A server |

外部专家增量在内部统一合入后启动，依赖 `ExpertExecutorRouter` 已就位。

### 11.3 术语重命名 + Handoff 交接（P6+ 独立增量）

| 任务 | 说明 |
|------|------|
| 前端术语重命名 | `/experts`->`/agents`；文案"专家"->"智能体"；执行模式"多专家协作"->"多智能体协作" |
| `HandoffEnvelope` | 新增结构化交接包 record + `HandoffEnvelopeBuilder` |
| `ExpertHubEngine` 交接改造 | `contextBlocks` 裸累积 -> `HandoffEnvelope` 注入 `injectedBlocks` |
| `PeerMsgSupport` | `formatTranscriptBlock` -> `formatHandoffEnvelope` |
| 智能体层 HITL 修复 | `bindHitlBridge` 按 `toolsJson` 含写工具动态开 |
| HITL 身份校验 | `HitlTokenRegistry` 存发起 userId，confirm 时比对 |
| 工具 output 脱敏 | `ToolAuditService` output 走 `DesensitizeClient.scrub` |
| 审计查询鉴权 | `AuditController` 按 conversationId/userId 归属校验 |
| transcript 脱敏 | `PeerRunAuditService` 落库前脱敏 content |
| Live | H1-H8 检查门 |

术语重命名可与 Handoff 独立先行；Handoff 安全缺口修复优先（H6-H8 影响线上安全）。

---

## 12. 自检清单

- [x] 无 TBD/TODO 占位需求
- [x] 与背景/方案一致：术语重命名 + 内部统一执行内核 + spawn 支持 expertId + 外部 A2A 接入 + Handoff 交接 + 安全缺口修复
- [x] 范围清晰：内部统一并入 P6（E5 已否决）；外部 A2A 独立 P6+；术语重命名 + Handoff 独立 P6+
- [x] 工具名/参数/AgentRunRequest 字段映射/A2A 事件映射/HandoffEnvelope 结构/Catalog/检查门无歧义
- [x] A2A 仅做 Client（消费外部 Agent Card），不做 Server
- [x] 内部/外部在 `ExpertCatalogEntry` 层统一，执行层按 source 分派
- [x] Handoff：结构化交接包替代裸 contextBlocks；三类不可乱传信息有明确处理；权限不足拒绝而非猜测
- [x] 术语重命名仅用户可见层，代码层保留 Expert* 避免命名冲突
- [x] 安全缺口（HITL/脱敏/鉴权）与 Handoff 一并修复
- [x] 不做兼容兜底（旧两阶段直接删，非 flag 切换）
