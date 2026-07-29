# 多智能体协作统一设计 - 技术设计

> **状态**：设计稿（待评审）  
> **日期**：2026-07-29  
> **编号**：阶段四增量（统一智能体定义 + A2A 外部接入 + Handoff 交接 + Agent Team 去中心化协作）  
> **前置**：[4.7.6 spawn_subagent](./2026-07-18-react-spawn-subagent-design.md) · [多专家协作（原设计）](./2026-07-07-expert-consultation-design.md) · [A2A Protocol v1.0](https://github.com/a2aproject/A2A)  
> **一句话**：将「专家」统一为「智能体」概念，内部走 `AgentRuntime.run` 统一内核、外部走 A2A 接入；智能体间协作从 Hub 固定轮次广播重构为去中心化 Agent Team 动态委派 + Handoff 结构化交接 + 共享 TeamState；同步扩展智能体定义模型（租户/知识库/权限/数据范围）。

> **本文档合并并取代以下 spec**：  
> - ~~`2026-07-24-expert-as-subagent-design.md`~~（内部统一 + 外部 A2A + Handoff，被本文档合并）  
> - ~~`2026-07-28-agent-team-design.md`~~（Agent Team，被本文档合并）  

---

## 0. 术语重命名映射

**全量重命名**：用户可见层 + 后端代码层 + DB + 服务名统一从「专家/Peer」改为「智能体/Team」。不再保留旧 `Expert*/Peer*` 代码类名。

| 旧术语 | 新术语 | 范围 |
|--------|--------|------|
| 专家（Expert） | 智能体（Agent） | 全量：代码 + DB + 前端 + 文档 |
| 多专家协作（peer-collab） | 多智能体协作（Agent Team） | 全量 |
| `expert-manager` 服务 | `agent-manager` | 服务名 + Nacos + 网关 |
| `expert_definition` 表 | `agent_definition` | DB |
| `com.sunshine.expert` 包 | `com.sunshine.agent` | Java |
| `com.sunshine.orchestrator.expert` 包 | `com.sunshine.orchestrator.team` | Java |
| `com.sunshine.orchestrator.peer` 包 | **删除** | Java |
| `Expert*` 类名 | `Agent*` 或 `Team*` | Java（存活类，详见 §15） |
| `Peer*` 类名 | **删除** | Java（Peer 全套删除） |
| `expert.*` / `peer.*` Catalog ID | `team.*` 或删除 | prompt-manager DB |
| `/experts` 路由 | `/agents` | 前端 |
| 文案"专家" | "智能体" | 前端 |

**命名冲突已排查**：`orchestrator.agent` 包（`AgentRuntime`/`AgentRunRequest`）与 `agent-manager` 服务 / `orchestrator.catalog` / `orchestrator.team` 是不同包路径，全量重命名无冲突（详见 §15.4）。

---

## 1. 背景与问题

### 1.1 现状：两套平行的子 Agent 路径 + 固定轮次协作

当前存在三种"子 Agent / 协作"机制，定义来源不同、执行内核不同、协作模式僵化：

| 维度 | 专家（peer-collab） | spawn_subagent | 单 Agent（ReAct/Workflow） |
|------|---------------------|----------------|---------------------------|
| 定义来源 | expert-manager DB 预定义 | 主 LLM 临时写 prompt | 路由层直接调 |
| 触发 | `PEER_COLLAB` 模式 / `$expert-id` | ReAct MAIN 元工具 | IntentRouter |
| 是否走 `AgentRuntime.run` | **否**，`ExpertPeerAgentFactory.create()` + `agent.call().block()` | **是** | **是** |
| 执行内核 | 独立 `ReActAgent` + 专用 Hook/Streamer 旁路 | `ReActAgentRuntime` | 同 spawn |
| 协作模式 | Hub 固定轮次顺序广播 | 无（主子单向） | 无 |

### 1.2 三个核心问题

1. **专家绕过统一入口**：`ExpertHubEngine.invokeAgent` 构造了 `AgentRunRequest` 却不交给 `AgentRuntime.run`，而是自己 `new ReActAgent` + `call().block()`，违反 CLAUDE.md「禁止绕过 `AgentRunRequest` 直接调 ReActAgent」。

2. **专家协作是「轮流表态」而非「团队协作」**：`ExpertHubEngine.run()` 是固定轮次 + 顺序广播——每个智能体对同一问题独立发言，`contextBlocks` 全量累积，彼此无任务交接、无动态委派。本质是「多视角咨询」，不是「团队协作」。

3. **智能体定义配置不足**：`expert_definition` 表仅有描述/工具/skills，无租户隔离、知识库范围、权限模型、数据访问范围，无法支撑 Handoff 权限裁剪。

### 1.3 与 Agent Team 的本质差距

| 维度 | 当前 peer-collab | Agent Team（目标） |
|------|------------------|---------------------|
| 控制模式 | Hub 固定轮次编排，智能体被动发言 | 去中心化，智能体自主决定"下一步找谁" |
| 通信机制 | 单向累积（contextBlocks 全量追加） | 点对点直接委派 + 共享 TeamState |
| 上下文管理 | 全量历史裸传 | HandoffEnvelope 结构化交接 + 按需查询 trace |
| 协作关系 | "轮流表态" | "动态接力"（根据意图委派给最合适的下一个智能体） |

### 1.4 决策

- **控制模式**：去中心化——每个智能体可自主决定下一步交给谁
- **peer-collab 去向**：完全替换——Agent Team 覆盖所有协作场景，删除 peer-collab 代码
- **框架依赖**：AgentScope 2.0 无原生多智能体协作原语（MsgHub 已删），Agent Team 全自研，复用已有 `AgentRuntime.run` + `AgentRunRequest.sub`
- **外部接入**：A2A Client 接入外部智能体，与内部智能体在 Catalog 层统一契约

---

## 2. 方案选型

### 2.1 内部智能体统一执行内核

`expert_definition` 本质是「命名的、可复用的子 Agent 配置」，与 `AgentRunRequest.sub(...)` 入参一一对应：

| expert_definition 字段 | AgentRunRequest.sub 入参 |
|------------------------|--------------------------|
| `system_prompt` | `systemOverlay` |
| `expert_skill_link.skill_id` | `skillId` |
| `tools_json` | `toolWhitelist` |
| （运行时补）`query` | `query` |
| `max_iters`（新增） | `maxIters` |

专家被锁死在 peer-collab 是历史分叉，不是本质差异。统一为：专家发言走 `AgentRuntime.run(AgentRunRequest.sub)`，消除 `ExpertPeerAgentFactory` / `ExpertSpeakHook` / `ExpertSpeakStreamer` 旁路。

### 2.2 spawn_subagent 支持 expertId

`SpawnSubagentTool` 入参从 `{prompt, label?}` 扩展为 `{prompt?, expertId?, label?}`。主 ReAct Agent 可点名预定义智能体当子 Agent。

### 2.3 A2A 外部智能体市场

外部智能体通过 A2A Agent Card 接入，后端适配成与内部一致的 `ExpertCatalogEntry` 契约，执行层按 `source` 分派。

### 2.4 Agent Team 去中心化协作

将 peer-collab 重构为 Agent Team：去中心化动态委派 + 共享 TeamState + Handoff 接力。智能体自主决定下一步交给谁，完全替换 peer-collab。

### 2.5 不采纳的方案

- **方案 C（薄桥，不改专家执行内核）**：最小改动但留下"专家两套执行路径"的债，违反「禁止兼容旧行为兜底」。
- **AS2 P6 peer-collab 迁移 HarnessAgent**：E5 评审否决（2026-07-25），peer-collab 保留全栈自研不迁移官方 Subagent；本设计直接替换而非迁移。

**结论：方案 A（内部统一）+ B（外部 A2A）+ D（Agent Team）组合。**

---

## 3. 智能体定义模型扩展

当前 `expert_definition` 表仅有 `display_name / description / system_prompt / enabled / tags_json / tools_json`，无法支撑 Handoff 权限裁剪、知识库范围、租户隔离等需求。需扩展。

### 3.1 现状缺口

| 维度 | 现状 | 问题 |
|------|------|------|
| 知识库范围 | 无配置；所有 Agent 共享会话级单一 `kbId`（`ChatConversationEntity.kbId`） | 不同智能体应访问不同知识库（如法务智能体只查法务库），无法裁剪 |
| 租户绑定 | `expert_definition` 无 `tenantId`；专家全局可见 | 跨租户场景下智能体应按租户隔离 |
| 权限模型 | 工具仅 `require_confirmation` + `side_effect`；沙箱策略硬编码 | 智能体需要更细粒度的数据访问权限 |
| 数据访问范围 | 完全不存在 `dataScope/kbScope` | 智能体调工具时无法限定可操作的数据范围 |
| 模型配置 | 无；所有智能体用同一 `OpenAIChatModel` | 不同智能体可能需要不同模型/温度 |
| 外部接入 | 无字段标记来源 | 无法区分内部/外部智能体 |

### 3.2 扩展后的 `expert_definition` 表（DDL 增量）

```sql
ALTER TABLE expert_definition
    ADD COLUMN tenant_id         VARCHAR(32) NOT NULL DEFAULT 'default' AFTER enabled,
    ADD COLUMN kb_scope_json     VARCHAR(512) NOT NULL DEFAULT '[]' AFTER tools_json,
    ADD COLUMN data_scope_json   TEXT AFTER kb_scope_json,
    ADD COLUMN permissions_json  VARCHAR(512) NOT NULL DEFAULT '{}' AFTER data_scope_json,
    ADD COLUMN model_config_json VARCHAR(512) NOT NULL DEFAULT '{}' AFTER permissions_json,
    ADD COLUMN max_iters         INT NOT NULL DEFAULT 2 AFTER model_config_json,
    ADD COLUMN max_handoffs      INT NOT NULL DEFAULT 5 AFTER max_iters,
    ADD COLUMN source            VARCHAR(16) NOT NULL DEFAULT 'INTERNAL' AFTER max_handoffs,
    ADD COLUMN agent_card_url    VARCHAR(512) AFTER source,
    ADD COLUMN auth_config_json  VARCHAR(512) AFTER agent_card_url,
    ADD COLUMN endpoint_override VARCHAR(512) AFTER auth_config_json,
    ADD INDEX idx_tenant_enabled (tenant_id, enabled),
    ADD INDEX idx_source (source);
```

### 3.3 新增字段说明

#### `tenant_id` -- 租户绑定

- `tenant_id = 'default'` 为全局共享（现有种子）；租户私有智能体 `tenant_id = 具体租户`
- Catalog 查询按 `tenant_id = ? OR tenant_id = 'default'` 过滤（与工具可见性一致）
- Agent Team 组队时只选当前租户可见的智能体

#### `kb_scope_json` -- 知识库范围

```json
["kb-legal", "kb-hr-policy"]
```

- `[]` = 继承会话级 `kbId`（现状，向后兼容）
- `["*"]` = 全部知识库
- 具体列表 = 仅可检索这些 kbId
- 运行时 `RagTool` 按 `kb_scope_json` 覆盖会话级 `kbId`

**改动点**：`RagTool.resolveKbId` 增加从智能体配置读取 `kbScope` 的逻辑；`AgentRunRequest` 增加 `kbScope` 字段透传。

#### `data_scope_json` -- 数据访问范围

```json
{
  "department": ["finance", "hr"],
  "expenseCategory": ["travel", "meal"],
  "maxAmount": 50000
}
```

- JSON 结构化，语义由工具侧解释
- 工具执行时，orchestrator 把 `data_scope` 注入 `ToolAuditContext`，业务工具（SDK）读取并按范围过滤
- **不强制**所有工具支持 `data_scope`；不支持的工具忽略（向后兼容）
- Handoff 交接时，`allowedTools` + `dataScope` 一并裁剪

#### `permissions_json` -- 权限配置

```json
{
  "toolConfirmation": "always",
  "sandboxWriteMode": "never",
  "allowDelegate": true,
  "allowFinishTask": true,
  "maxConcurrentHandoffs": 3
}
```

- `toolConfirmation`：`always` / `never` / `inherit`（inherit = 读工具 `require_confirmation`）。覆盖当前专家层 HITL 硬关闭（`bindHitlBridge(..., false)`）的问题
- `sandboxWriteMode`：`never` / `always` / `smart`（覆盖 `SandboxWriteHitlMode`）
- `allowDelegate` / `allowFinishTask`：控制能否在 Team 内委派/完成
- `maxConcurrentHandoffs`：同时被委派的次数上限

#### `model_config_json` -- 模型配置

```json
{
  "model": "gpt-4o",
  "temperature": 0.3
}
```

- `{}` = 继承全局默认（现状）
- 非空 = 该智能体用指定模型/温度（`AgentRuntime` 读取并覆盖 `OpenAIChatModel.builder`）

#### `max_iters` / `max_handoffs` -- 执行限制

- `max_iters`：单次被委派时 ReAct 最大轮次（现有硬编码 2，改为可配置）
- `max_handoffs`：该智能体在单次 Team 协作中最多被委派几次（防止循环）

#### `source` / `agent_card_url` / `auth_config_json` / `endpoint_override` -- 外部 A2A 接入

- `source`：`INTERNAL`（默认）/ `EXTERNAL`
- `agent_card_url`：外部智能体 Agent Card URL（`source=EXTERNAL` 时必填）
- `auth_config_json`：鉴权配置 JSON（bearer token / apikey 引用 Nacos 密钥）
- `endpoint_override`：可选；覆盖 Agent Card 内 `supportedInterfaces[0].url`（内网代理场景）
- 外部智能体的 `system_prompt` / `tools_json` / `skillIds` **留空**（由远端 Agent 自治）

### 3.4 ExpertCatalogEntry 扩展（合并）

```java
public record ExpertCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemPrompt,       // EXTERNAL 时 null
        List<String> skillIds,     // EXTERNAL 时空
        List<String> tags,
        String toolsJson,          // EXTERNAL 时空
        boolean enabled,
        // 智能体定义模型扩展
        String tenantId,           // 租户绑定
        List<String> kbScope,      // 知识库范围
        String dataScopeJson,      // 数据访问范围
        String permissionsJson,    // 权限配置
        String modelConfigJson,    // 模型配置
        int maxIters,              // 单次 ReAct 上限
        int maxHandoffs,           // Team 内被委派上限
        // 外部 A2A
        ExpertSource source,       // INTERNAL / EXTERNAL
        String agentCardUrl,
        String authConfigJson,
        String endpointOverride
) {
    public enum ExpertSource { INTERNAL, EXTERNAL }
}
```

### 3.5 运行时透传链路

```
expert_definition (DB)
  -> ExpertCatalogEntry (DTO)
  -> AgentRunRequest.sub(...) 新增字段：
       kbScope / dataScopeJson / permissionsJson / modelConfigJson / maxHandoffs
  -> ReActAgentRuntime：
       modelConfig    -> OpenAIChatModel.builder 覆盖
       kbScope        -> RagTool.resolveKbId 优先用 kbScope
       permissionsJson -> HITL bindHitlBridge 覆盖 / 沙箱 WriteMode 覆盖
       dataScopeJson  -> StepEventBridge.ToolAuditContext 注入
  -> 工具执行：
       ToolAuditContext.dataScope -> 业务工具读取并过滤
       ToolAuditContext.kbScope   -> RagTool 检索范围
```

### 3.6 前端 `/agents` 配置页扩展

| 配置区块 | 字段 |
|----------|------|
| 基础（现有） | ID / 展示名 / 描述 / systemPrompt / 启用 / tags |
| 工具与技能（现有） | toolsJson / skillIds |
| 知识库范围（新） | kbScope 多选（从 rag-service `/api/rag/kb/list` 拉） |
| 数据范围（新） | dataScope JSON 编辑器 |
| 权限（新） | toolConfirmation 下拉 / sandboxWriteMode 下拉 / allowDelegate 开关 / allowFinishTask 开关 |
| 模型（新） | model 下拉 / temperature 滑块 |
| 执行限制（新） | maxIters / maxHandoffs 数值输入 |
| 租户（新） | tenantId（admin 可见，普通用户只看本租户） |
| 外部接入（A2A） | source / agentCardUrl / authConfigJson / endpointOverride（外部 tab） |

---

## 4. 核心概念

### 4.1 Agent Team

一组智能体组成的协作团队，围绕一个用户目标动态协作。不预设轮次、不全员广播；由当前持有"发言权"的智能体决定下一步动作（继续处理 / 委派给队友 / 请求用户补充 / 完成任务）。

### 4.2 TeamState（共享状态）

所有团队成员可读写的共享状态，区别于单 Agent 的 `AssembledContext`（私有的、隔离的）：

```java
public record TeamState(
    String teamId,
    String objective,                    // 团队目标（用户原始问题）
    List<ConfirmedFact> confirmedFacts,  // 已确认事实（带来源 + verified 标记）
    List<TeamTask> tasks,                // 任务分解（动态增减）
    List<HandoffRecord> handoffLog,      // 交接历史（谁交给谁，带了什么）
    Map<String, Object> sharedVars,      // 共享变量（工具产出/中间结果）
    List<String> openQuestions,          // 未解决问题
    TeamStatus status                    // ACTIVE / COMPLETED / BLOCKED
) {}
```

- 存储：Redis（`sunshine:team:{teamId}`），TTL 与会话绑定
- 读写：每次智能体被委派时，读取当前 TeamState 快照注入 context；发言后更新 TeamState

### 4.3 HandoffEnvelope（交接包）

智能体间任务交接的结构化数据包，替代裸 `contextBlocks` 全量累积传递：

```java
public record HandoffEnvelope(
    String fromAgentId,             // 交接发起方
    String toAgentId,               // 接收方（null = 交回 Team/用户）
    String objective,               // 本次交接的子目标
    List<ConfirmedFact> facts,      // 已确认事实（带来源标记 + verified）
    List<String> evidenceRefs,      // 证据链接（工具结果摘要/文档引用，已脱敏）
    List<AttemptedAction> attempted,// 已尝试动作 + 失败原因
    List<String> openQuestions,     // 未解决问题
    List<String> allowedTools,      // 权限边界：可调用工具（发起方 ∩ 接收方）
    String dataScopeJson,           // 数据访问范围（取交集）
    String expectedOutput,          // 期望输出格式
    HandoffReason reason            // DELEGATE / ESCALATE / HAND_BACK / COMPLETE
) {
    public record ConfirmedFact(String fact, String source, boolean verified) {}
    public record AttemptedAction(String action, String result, String failureReason) {}
    public enum HandoffReason { DELEGATE, ESCALATE, HAND_BACK, COMPLETE }
}
```

### 4.4 去中心化委派元工具

新增元工具 `delegate_to_agent`（仅 Team 成员可调用），智能体在 ReAct 中自主决定委派：

```java
@Tool(name = "delegate_to_agent",
      description = "将当前子任务委派给团队成员；交接上下文经 HandoffEnvelope 结构化过滤")
public String delegate(
    @ToolParam(name = "agentId", description = "目标团队成员 ID") String agentId,
    @ToolParam(name = "objective", description = "交接子目标") String objective,
    @ToolParam(name = "facts", description = "已确认事实（JSON）") String factsJson,
    @ToolParam(name = "openQuestions", description = "未解决问题（JSON 数组）") String openQuestionsJson,
    @ToolParam(name = "expectedOutput", description = "期望输出") String expectedOutput) {
    // 1. 构建 HandoffEnvelope
    // 2. 权限裁剪（allowedTools = 当前 agent tools ∩ 接收 agent tools）
    // 3. 敏感信息过滤
    // 4. 调 AgentRuntime.run(接收 agent, envelope)
    // 5. 接收 agent 产出回传当前 agent 作为 tool result
    // 6. 更新 TeamState
}
```

与 `spawn_subagent` 的区别：`spawn_subagent` 是主 Agent -> 子 Agent（中心化，子无自主权）；`delegate_to_agent` 是团队成员 -> 团队成员（去中心化，对等委派）。

### 4.5 与 spawn_subagent 的关系

| 能力 | spawn_subagent（保留） | Agent Team（新增） |
|------|----------------------|---------------------|
| 控制模式 | 中心化（主->子，子无自主权） | 去中心化（成员对等委派） |
| 上下文 | 主写 prompt，子隔离 | HandoffEnvelope + 共享 TeamState |
| 通信 | 单向返回值 | 双向（委派 + 返回 + 共享状态） |
| 适用 | 独立并行子任务 | 强依赖迭代配合 |

两者正交，共存。

---

## 5. 架构与改动

### 5.1 整体链路

```
用户问题 -> 意图路由 -> AGENT_TEAM 模式
  -> TeamOrchestrator.createTeam(roster, objective)
       -> 初始化 TeamState（Redis）
       -> 选初始 agent（LLM 按 objective 选最合适的起步 agent）
  -> TeamOrchestrator.run(startAgentId)
       loop:
         expertExecutorRouter.invokeExpert(currentAgent, teamState, handoffEnvelope)
           ├─ source=INTERNAL -> AgentRuntime.run(AgentRunRequest.sub)
           └─ source=EXTERNAL -> ExternalExpertClient.invoke (A2A)
         if agent 调 delegate_to_agent:
           -> 接收 agent 成为新 currentAgent，带 HandoffEnvelope
           -> 回到 loop
         if agent 调 finish_task 或无后续委派:
           -> TeamSynthesizer 汇总 TeamState -> message.content 流式
           -> break
         if 超过 maxHandoffs / 超时 / 死锁检测:
           -> 强制收束 -> TeamSynthesizer
```

### 5.2 内部智能体执行内核统一

`ExpertHubEngine.invokeAgent` 不再自己 `new ReActAgent` + `call().block()` + `ExpertSpeakStreamer.streamSpeak`，改为走 `AgentRuntime.run`。两阶段（gather + speak）合并为单阶段 ReAct：

| 现状两阶段 | 合并后单阶段 ReAct |
|-----------|-------------------|
| 阶段1 gather：`PromptComposer.composeReactInputs` + `agent.call().block()` + `ExpertSpeakHook` | ReAct 自然产出：expert.systemPrompt + skill overlay 约束"先调工具再给结论"，gather instruction 作为 `injectedBlocks` 注入 |
| 阶段2 speak：`ExpertSpeakStreamer.streamSpeak` + `peer.speak-prompt` 模板 + Gateway 直链 | ReAct 终态正文即专家发言；speak 模板的"基于检索材料发言"约束融入 expert.systemPrompt |

### 5.3 spawn_subagent 支持 expertId

`SpawnSubagentTool.spawnSubagent` 入参扩展为 `{prompt?, expertId?, label?}`：

| 参数组合 | 行为 |
|----------|------|
| `prompt` 有，`expertId` 空 | 现有逻辑：临时子 Agent，`mode-overlay.subagent`，无 skill |
| `expertId` 有，`prompt` 空 | 预定义智能体子 Agent：systemPrompt/skill/tools 来自 expert，query = 主 Agent 当前任务上下文 |
| 两者都有 | 智能体配置 + 主写的 prompt 叠加 |
| 两者都空 | 报错 |

`expertId` 解析：从 `ExpertCatalogService.find(expertId)` 取 `ExpertCatalogEntry`，填入 `systemOverlay` / `skillId` / `toolWhitelist` / `maxIters`。外部智能体（`source=EXTERNAL`）也支持，走 `ExpertExecutorRouter` 分派。

### 5.4 组件清单

| 组件 | 改动 | 替代 |
|------|------|------|
| `TeamOrchestrator` | 创建 Team / 选起步 / 调度 loop / 死锁检测 | `ExpertConsultationExecutor` |
| `TeamStateService` | Redis 读写 TeamState + TTL | （新） |
| `TeamHandoffService` | 构建/裁剪/脱敏 HandoffEnvelope | （新） |
| `DelegateToAgentTool` | 元工具：成员间委派 | `ExpertSpeakStreamer`（删） |
| `FinishTaskTool` | 元工具：声明完成 | （新） |
| `TeamSynthesizer` | TeamState -> 流式正文 | `ConsultationSynthesizer`（改造） |
| `TeamTimelineBridge` | Team 步骤折叠 + 委派箭头 | `ExpertTimelineSupport` |
| `TeamRouter` | 选起步 agent / NO_ACTION 时选下一个 | （新） |
| `ExpertExecutorRouter` | 按 `source` 分派 INTERNAL/EXTERNAL | （新） |
| `ExternalExpertClient` | A2A Client | （新） |
| `SpawnSubagentTool` | 入参加 `expertId` + `resolveExpert` | 不变（扩展） |
| `AgentRuntime.run` | 统一执行内核（复用，不改） | 不变 |

### 5.5 删除清单（完全替换 peer-collab）

| 删除组件 | 原职责 |
|----------|--------|
| `ExpertConsultationExecutor` | peer-collab 入口执行器 |
| `ExpertHubEngine` | 固定轮次 Hub 调度 |
| `ExpertPeerAgentFactory` | 专家专用 ReActAgent 工厂 |
| `ExpertSpeakHook` | 专家步 Timeline Hook |
| `ExpertSpeakStreamer` | 阶段2 发言流式 |
| `ExpertRoundCoordinatorService` | 轮次 continue 判定 + 反应式选人 |
| `PeerMsgSupport` | transcript 格式化 |
| `PeerRunAuditService` | peer_run 落库 |
| `PeerSynthesisProperties` | min/max rounds 配置 |
| ExecutionMode `PEER_COLLAB` | 改为 `AGENT_TEAM` |

---

## 6. 外部智能体市场（A2A 接入）

### 6.1 设计原则：契约统一，执行分派

外部智能体与内部智能体在 `ExpertCatalogEntry` 层统一，对 `TeamOrchestrator` / `SpawnSubagentTool` 透明。执行层按 `source` 字段分派：

| source | 执行路径 | 通信 |
|--------|----------|------|
| `INTERNAL` | `AgentRuntime.run(AgentRunRequest.sub)` | 同进程 in-memory |
| `EXTERNAL` | `ExternalExpertClient.invoke(query, context)` -> A2A `tasks/sendSubscribe` | HTTP + SSE |

### 6.2 A2A Agent Card

遵循 A2A v1.0，外部智能体服务在 `/.well-known/agent-card.json` 发布 Agent Card：

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

### 6.3 外部智能体注册

admin 在 `/agents` 外部 tab 填入 agentCard URL -> expert-manager 拉取 Agent Card -> 回填 `display_name` / `description` / `tags`（可编辑）-> 存库 `source=EXTERNAL`。

### 6.4 ExternalExpertClient（A2A Client）

```java
@Component
public class ExternalExpertClient {
    private final WebClient webClient;

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
                .bodyToFlux(String.class)
                .flatMap(line -> mapA2aEvent(line, expert));
    }
}
```

#### A2A 事件 -> StreamToken 映射

| A2A 事件 | StreamToken |
|----------|-------------|
| `TaskStatusUpdateEvent`（state=WORKING） | `step`（active 文案） |
| `TaskArtifactUpdateEvent`（parts[].text） | `content`（正文 delta） |
| `TaskStatusUpdateEvent`（state=INPUT_REQUIRED） | `step`（HITL 待确认） |
| `TaskStatusUpdateEvent`（state=COMPLETED） | 终态 `step`（after） |
| `TaskStatusUpdateEvent`（state=FAILED/CANCELED/REJECTED） | 终态 `step`（fail/cancel） |

### 6.5 执行分派：ExpertExecutorRouter

```java
Flux<StreamToken> invokeExpert(ExpertCatalogEntry expert, String query, List<String> contextBlocks, ...) {
    return switch (expert.source()) {
        case INTERNAL -> agentRuntime.run(buildInternalSubRequest(expert, query, contextBlocks, ...));
        case EXTERNAL -> externalExpertClient.invoke(expert, query, contextBlocks);
    };
}
```

- Team 中 `TeamOrchestrator` 调 `expertExecutorRouter.invokeExpert`（不再直接 `AgentRuntime.run`）
- ReAct `SpawnSubagentTool` 指定 `expertId` 时同样走 `expertExecutorRouter.invokeExpert`

### 6.6 前端 `/agents` 双 tab

| Tab | 内容 |
|-----|------|
| 内部智能体 | 现有 CRUD（systemPrompt / skill / tools / kb / 权限 / 模型编辑） |
| 外部智能体 | 列表 + 新增：填 agentCard URL -> 拉取预填 -> 编辑展示信息 + 鉴权配置 |

外部智能体卡片标记「外部」badge。Chat `$` 补全和 ReAct spawn `expertId` 两个 tab 统一列表，前端补全时展示 source badge 区分。

---

## 7. Handoff 交接原则

### 7.1 问题：现状是全量历史裸传

当前 peer-collab 中，智能体间上下文经 `contextBlocks` **全量累积传递**：

```
contextBlocks = [用户问题, 【智能体A】完整发言, 【智能体B】完整发言, ...]
```

无摘要、无截断、无权限裁剪。这与 Handoff 原则要求的"受控转派 + 上下文过滤"相悖。Agent Team 中改用 `HandoffEnvelope` 结构化交接 + 共享 `TeamState`。

### 7.2 三类不可乱传信息的处理

| 类别 | 风险 | Handoff 处理 |
|------|------|-------------|
| **敏感信息** | userId/tenantId/支付/内部工具返回数据 -> 隐形越权 | 交接包只含 `objective` + `confirmedFacts`（已脱敏）；userId/tenantId **不进** envelope（`AssembledContext.forSubAgent()`=empty 已隔离）；工具结果摘要进 `evidenceRefs` 前经 `DesensitizeClient.scrub` |
| **噪声/错误假设** | 模型试错、工具失败、被用户否定的内容带偏新智能体 | `attemptedActions` 标明失败原因；`confirmedFacts` 区分 `verified=true/false`；完整历史保留在后台 trace，**不默认传递**；接收方可按需查询 trace |
| **责任边界** | 交接后谁负责最终回答、谁能调工具 | `recipientAgentId` 明确接手者；`allowedTools` 裁剪权限（按接收方 `toolsJson` 白名单求交）；`dataScope` 取交集；TeamSynthesizer 仍是最终正文负责方 |

### 7.3 交接流程（Agent Team 演进）

```
智能体A 执行完成，调用 delegate_to_agent
  -> TeamHandoffService.build(fromAgent=A, toAgent=B, params)
       ├─ 提取 confirmedFacts（从 A 发言 + 工具结果）
       ├─ 裁剪 allowedTools = A.toolsJson ∩ B.toolsJson
       ├─ 裁剪 dataScope = A.dataScope ∩ B.dataScope
       ├─ 脱敏 evidenceRefs（DesensitizeClient.scrub）
       └─ 产出 HandoffEnvelope
  -> 传递给智能体B：envelope 序列化为结构化文本注入 injectedBlocks
  -> 更新 TeamState（handoffLog + confirmedFacts + openQuestions）
  -> 完整 transcript 保留在 TeamState trace（Redis + 可选落库）
```

**与 peer-collab Handoff 的演进**：

| 维度 | peer-collab 场景 | Agent Team |
|------|------------------|------------|
| 交接包产出方 | Hub 编排器构建 | **智能体自主产出**（delegate_to_agent 参数） |
| 交接对象 | 下一轮发言者（Hub 决定） | **智能体自主指定**（agentId 参数） |
| 共享状态 | 无（仅 contextBlocks 累积） | **TeamState 共享**（所有成员可读写） |
| 权限裁剪 | Hub 按 roster 求交 | 智能体声明 allowedTools，TeamHandoffService 校验 |

### 7.4 权限不足处理

接收方智能体 `toolsJson` 白名单不包含交接包 `allowedTools` 中的某工具时：
- **拒绝接手**该子任务，返回"权限不足：需 X 工具但未授权"
- 或**请求重新授权**：向 TeamOrchestrator 发信号，由编排层决定是否升级权限或换人
- **禁止**拿着被裁剪的信息猜测答案

### 7.5 质量验证指标

| 指标 | 定义 | 采集方式 |
|------|------|----------|
| 重复提问率 | 接收方重新问了已被前序智能体回答的问题 | transcript 对比 LLM 判定（离线） |
| 错误继承率 | 接收方基于未确认的猜测继续推理 | `confirmedFacts.verified=false` 被引用计数 |
| 敏感字段泄露率 | 交接包含 userId/tenantId/原始支付数据 | 自动化扫描 envelope 内容 |

### 7.6 现有安全缺口的修复（Handoff 落地时一并修）

| 缺口 | 现状 | 修复 |
|------|------|------|
| 智能体层 HITL 被关闭 | `ExpertHubEngine.bindHitlBridge(..., false)` | 按 `permissions.toolConfirmation` 动态决定（always/never/inherit） |
| HITL 确认无身份校验 | `confirmTool` 仅凭 token，不比对发起用户 | `HitlTokenRegistry` 注册时存发起 userId，confirm 时校验 |
| 工具 output 未脱敏 | `ToolAuditService` 只脱敏 params，output 仅截断 240 字符 | output 也走 `DesensitizeClient.scrub` |
| 审计查询无鉴权 | `AuditController` 三接口不校验归属 | 按 conversationId/userId 归属校验 |
| transcript 全文不脱敏 | `peer_run.transcript_json` 含 userQuery 原文 | 落库前对 content 脱敏 |

---

## 8. 执行流程详解

### 8.1 Team 创建与起步

```
TeamOrchestrator.createTeam(roster, objective, userId, tenantId):
  1. teamId = UUID
  2. teamState = TeamState(teamId, objective, [], [], [], {}, [], ACTIVE)
  3. Redis SET sunshine:team:{teamId}
  4. startAgentId = teamRouter.selectStartAgent(roster, objective)
     // LLM 按 objective + roster description 选最合适的起步 agent
  5. 发 Timeline 步：team-convene（"已组建团队：A, B, C"）
  6. return run(startAgentId, initialHandoff=null)
```

### 8.2 单次 Agent 执行（Team loop 一步）

```
TeamOrchestrator.run(currentAgentId, incomingEnvelope):
  agent = roster.find(currentAgentId)
  teamState = teamStateService.load(teamId)
  // 注入：teamState 快照 + incomingEnvelope + agent.systemPrompt + agent.tools
  request = AgentRunRequest.sub(
      forSubAgent(),
      query = composeTeamQuery(objective, teamState, incomingEnvelope),
      injectedBlocks = [teamStateSummary(teamState), handoffEnvelopeText(incomingEnvelope)],
      skillId = agent.primarySkillId(),
      toolWhitelist = agent.toolsJson,
      systemOverlay = agent.systemPrompt + team-collaboration-overlay,
      maxIters = agent.maxIters)
  // 注册 delegate_to_agent + finish_task 元工具（仅 Team 成员）
  registerTeamMetaTools(request, agent, teamState)
  // 执行
  result = expertExecutorRouter.invokeExpert(agent, query, injectedBlocks)
  // 解析 result：agent 是否调用了 delegate_to_agent / finish_task
  action = parseTeamAction(result)
  switch action:
    case DELEGATE(toId, envelope):
      updateTeamState(teamState, agent, envelope)
      logHandoff(teamState, agent.id, toId, envelope)
      return run(toId, envelope)   // 递归委派
    case FINISH:
      markTeamCompleted(teamState)
      return synthesize(teamState)
    case NO_ACTION:
      // agent 未委派也未完成 -> Team 编排器决定下一步
      nextAgent = teamRouter.selectNext(teamState, roster)
      if nextAgent == null: return synthesize(teamState)
      return run(nextAgent, null)
```

### 8.3 死锁与收束保护

| 情况 | 处理 |
|------|------|
| 超过 maxHandoffs（默认 10） | 强制 synthesize |
| 同一 agent 连续被委派 3 次无进展 | 强制 synthesize |
| 循环检测（A->B->A->B） | 强制 synthesize |
| 超时（默认 300s） | 强制 synthesize |
| 所有 agent 都 NO_ACTION | synthesize |

### 8.4 TeamSynthesizer

与 `ConsultationSynthesizer` 区别：输入不是 transcript（发言列表），而是 **TeamState**（结构化状态：objective + confirmedFacts + tasks + handoffLog）。

```
TeamSynthesizer.synthesize(teamState):
  prompt = catalog "team.synthesis-prompt"
    .replace("{objective}", teamState.objective)
    .replace("{confirmedFacts}", formatFacts(teamState.confirmedFacts))
    .replace("{tasks}", formatTasks(teamState.tasks))
    .replace("{handoffLog}", formatHandoffs(teamState.handoffLog))
  return llmGatewayClient.streamDirectly(prompt)  // 流式 message.content
```

---

## 9. Timeline / UI

### 9.1 主时间线步骤形态

```
识别意图     -> …将由智能体团队处理…
团队组建     -> 已组建团队：制度智能体、财务智能体（可展开起步理由）
制度智能体   -> 正在分析… -> 摘要 | 展开详情（含 HandoffEnvelope）
  ↳ 委派给财务智能体（可展开交接包：目标/事实/未解决问题）
财务智能体   -> 正在处理… -> 摘要
  ↳ 委派给制度智能体
制度智能体   -> 正在补充… -> 摘要
团队结论     -> （消息正文区流式输出 Synthesizer 汇总）
```

**关键区别**（vs peer-collab）：
- 不显示"第 N 轮"（本设计连轮次概念都没有）
- 每次委派显示**委派箭头**（谁->谁），可展开 HandoffEnvelope
- 智能体可被多次委派，Timeline 按委派顺序排列

### 9.2 Step ID

| id | 说明 |
|----|------|
| `team-convene` | 团队组建 |
| `agent-{agentId}-h{handoffSeq}` | 该智能体第几次被委派（仅 id，界面不展示 seq） |
| `team-synthesize` | 团队结论汇总（可选 Timeline 步，或直接进 message.content） |

`phase=team`；`metadata`: `agentId`, `displayName`, `handoffSeq`, `handoffFrom?`（委派方）, `handoffReason?`, `source`（INTERNAL/EXTERNAL）

### 9.3 前端改动

- 新增 `TeamStepPanel`：主行 `label` + `summary`；展开含 HandoffEnvelope（目标/事实/未解决问题/已尝试动作）
- 委派箭头：两个 agent 步之间显示 `->` 连线 + 可展开交接包
- `/agents` 页（术语重命名后）增加「团队协作」配置：哪些智能体可组队、默认 maxHandoffs/超时
- Chat `$A $B` 仍触发 Team 模式（路由不变，执行模式改为 AGENT_TEAM）
- 术语重命名：`/experts` -> `/agents`；文案"专家"->"智能体"；执行模式"多专家协作"->"多智能体协作"

### 9.4 Timeline / 取消复用

| 组件 | Team 智能体步 | spawn_subagent |
|------|----------------|----------------|
| 主时间线卡 | `agent-{agentId}-h{seq}` | `subagent-{runId}` |
| 折叠 Bridge | `TeamTimelineBridge` | `SpawnSubagentTimelineBridge` |
| 取消 | Team 整体取消 | `SpawnRunRegistry` 单独取消 |
| HITL | 复用 SUB `bindHitlBridge` | 同 |

ReAct 中 spawn_subagent 指定 expertId 时，主卡仍为 `subagent-{runId}`，但 `metadata` 增加 `expertId` / `expertName`，前端卡片可展示智能体名作为 label。

---

## 10. Catalog / 提示词

### 10.1 新增（Agent Team）

| Catalog id | 用途 |
|------------|------|
| `team.collaboration-overlay` | Team 成员 systemPrompt 叠加：说明可调 `delegate_to_agent` / `finish_task`，如何决定委派 |
| `team.start-agent-prompt` | 选起步 agent 的 LLM prompt |
| `team.synthesis-prompt` | TeamSynthesizer 汇总模板（基于 TeamState） |
| `team.handoff-instruction` | 注入 injectedBlocks：HandoffEnvelope 渲染为结构化文本 |
| `timeline.steps.team-convene` | 团队组建步文案 |
| `timeline.steps.team-agent` | 智能体执行步文案（active/after） |
| `react.delegate-to-agent.desc` | delegate_to_agent 元工具描述 |
| `react.finish-task.desc` | finish_task 元工具描述 |

### 10.2 废弃（peer-collab 专属）

- `peer.gather-instruction` / `peer.speak-prompt` / `peer.synthesis-prompt` / `peer.round-continue-prompt` / `peer.round-speakers-prompt` / `expert.coordinator-prompt` / `expert.complexity-prompt`
- `timeline.steps.expert` / `timeline.steps.expert-convene`

### 10.3 保留

| Catalog id | 说明 |
|------------|------|
| `mode-overlay.subagent` | 不改；指定 expertId 时不生效（被 expert.systemPrompt 覆盖） |
| `expert.*` 种子 systemPrompt | 需同步调整：写明"须先调用工具检索再给结论"，吸收原 speak-prompt 约束 |
| `timeline.steps.subagent` | 不改；spawn 指定 expertId 时 label 取智能体 displayName |
| `react.subagent.cancel-result` | 不改 |

> 提示词 SSOT 仍在 prompt-manager Catalog，禁止 Java 硬编码。

---

## 11. 调用契约

### 11.1 Agent Team 路径

```
TeamOrchestrator
  └─ run(currentAgentId, incomingEnvelope)
       per agent:
         expertExecutorRouter.invokeExpert(agent, query, injectedBlocks)
           ├─ source=INTERNAL: AgentRuntime.run(AgentRunRequest.sub(
           │    forSubAgent(), query, teamStateSummary+handoffEnvelopeText,
           │    userId, tenantId, msgId,
           │    agent.primarySkillId(), agent.toolWhitelist,
           │    agent.systemPrompt + team-collaboration-overlay, agent.maxIters))
           │    ├─ ReActAgentRuntime
           │    ├─ 子 think/tool 经 bridge 折叠进 agent 步 subSteps
           │    └─ 终态正文 -> step_delta(result) 流式
           └─ source=EXTERNAL: ExternalExpertClient.invoke(agent, query, contextBlocks)
                └─ A2A tasks/sendSubscribe -> StreamToken
       └─ TeamSynthesizer.synthesize（基于 TeamState）
```

### 11.2 ReAct spawn_subagent 路径（扩展后）

```
主 ReAct (MAIN)
  └─ tool_call: spawn_subagent({ prompt?, expertId?, label? })
        ├─ expertId 为空: 现有逻辑（mode-overlay.subagent，无 skill）
        └─ expertId 非空: ExpertCatalogService.find(expertId)
              ├─ source=INTERNAL: AgentRunRequest.sub(... expert.systemPrompt / skillId / tools ...)
              │   -> AgentRuntime.run(request) -> 终态文本回主 ReAct 作为 tool result
              └─ source=EXTERNAL: ExpertExecutorRouter.invokeExpert
                  -> ExternalExpertClient -> A2A task 流式回 tool result
```

### 11.3 AgentRunRequest.sub 字段映射（智能体子 Agent）

| 字段 | 来源 |
|------|------|
| `role` | `SUB` |
| `memory` | `AssembledContext.forSubAgent()` |
| `query` | Team: objective + teamState + handoffEnvelope；spawn: 主写的 prompt |
| `injectedBlocks` | Team: teamStateSummary + handoffEnvelopeText；spawn: `List.of()` |
| `skillId` | `agent.primarySkillId()` |
| `toolWhitelist` | `ExpertToolsJson.parse(agent.toolsJson())` |
| `systemOverlay` | `agent.systemPrompt()`（+ Team 叠加 `team-collaboration-overlay`） |
| `maxIters` | `agent.maxIters`（默认 2） |
| `kbScope` | `agent.kbScope`（新增透传） |
| `dataScopeJson` | `agent.dataScopeJson`（新增透传） |
| `permissionsJson` | `agent.permissionsJson`（新增透传） |
| `modelConfigJson` | `agent.modelConfigJson`（新增透传） |
| `maxHandoffs` | `agent.maxHandoffs`（新增透传） |
| `timeline` | `SUB_COMPRESSED` |
| `conversationId` | Team: null；spawn: 主会话（沙箱复用） |

---

## 12. 边界与非目标

**做**

- 术语重命名：用户可见层「专家」->「智能体」、「多专家协作」->「多智能体协作」
- 内部智能体执行内核统一到 `AgentRuntime.run`，消除 `ExpertPeerAgentFactory` / `ExpertSpeakHook` / `ExpertSpeakStreamer` 旁路
- spawn_subagent 支持 `expertId` 调用预定义智能体（内部/外部均可）
- 智能体定义模型扩展：租户/知识库/权限/数据范围/模型/执行限制
- 去中心化 Agent Team：智能体自主委派 + 共享 TeamState + Handoff 接力
- 完全替换 peer-collab：删除 ExpertHubEngine 全套
- 外部智能体通过 A2A Agent Card 接入，`ExpertCatalogEntry` 统一契约 + 按 `source` 分派执行
- 前端 `/agents` 双 tab（内部/外部）；外部智能体 agentCard URL 注册 + 预填
- Handoff 交接包：结构化 `HandoffEnvelope` 替代裸 `contextBlocks`；上下文过滤 + 权限裁剪 + 敏感信息脱敏
- 修复安全缺口：智能体层 HITL 动态开关 / HITL 身份校验 / 工具 output 脱敏 / 审计查询鉴权 / transcript 脱敏

**不做**

- Sunshine 不实现 A2A Server（只做 A2A Client 消费外部智能体）
- 外部智能体的本地 systemPrompt / skill / tools overlay（远端 Agent 自治）
- 外部智能体 A2A push notifications（仅 streaming 模式）
- 智能体在 Plan/Workflow agent 节点中的直接引用（节点已有 `params.skill` / `params.systemOverlay`，可后续增量）
- 智能体嵌套调用（SUB 仍禁止再 spawn，与 spawn_subagent 一致）
- 真正的点对点实时通信（当前仍是"委派-执行-返回"的顺序模式，非并行实时消息总线；未来可扩展为事件驱动）
- 智能体并行执行（v1 顺序委派；并行委派可后续扩展为 `delegate_to_agents` 批量）
- 删除 spawn_subagent（保留，与 Team 正交：spawn 是主子中心化，Team 是对等去中心化）

---

## 13. 风险与对策

| 风险 | 对策 |
|------|------|
| gather+speak 合并后智能体发言质量下降（不再"先检索再发言"） | expert.systemPrompt + gather-instruction injectedBlock 双重约束；种子智能体文案同步调整；Live 对比前后发言质量 |
| Team Timeline 形态变化导致前端回归 | 智能体步 `agent-{id}-h{seq}` + `step_delta(result)` 形态与旧 expert 步类似，驱动源从 streamSpeak 变为 AgentRuntime StreamToken，前端改动可控 |
| spawn_subagent expertId 与 `$` 路由冲突 | 不冲突：`$` 是路由层 L0 硬绑定（进 AGENT_TEAM），expertId 是 ReAct 元工具内主 LLM 主动点名（进 SUB），两者正交 |
| 智能体不委派也不完成（NO_ACTION 死循环） | maxHandoffs + 超时 + 循环检测 + 强制 synthesize |
| 智能体乱委派（A->B->A 循环） | 循环检测（最近 4 次委派出现重复模式即强制收束） |
| TeamState 膨胀（handoffLog 无限增长） | handoffLog 保留最近 20 条 + 摘要旧的 |
| 智能体委派给不在 roster 的 agent | delegate_to_agent 校验 agentId 在 roster 内 |
| 敏感信息经 TeamState 泄露给其他成员 | TeamHandoffService 脱敏 + allowedTools 权限裁剪 |
| 比 peer-collab 慢（多轮 LLM 委派决策） | 委派决策可缓存；maxHandoffs 上限控制；复杂场景才用 Team（简单问题走 ReAct） |
| 路由层误判（简单问题进了 Team） | 路由 L1/L3 须区分"需团队协作"vs"单 Agent 可解"；Team 是高成本模式 |
| 外部智能体网络不可达/超时 | `ExternalExpertClient` 设超时 + 重试 + 降级（返回错误 tool result，主 Agent 可改用内部智能体）；Team 中某外部智能体失败不影响其他 |
| 外部智能体鉴权泄露 | `auth_config_json` 只存密钥引用（如 `nacos:expert.auth.legal`），实际 token 从 Nacos 加密配置读取，不落库明文 |
| 外部智能体返回非 text artifact | A2A client 只解析 `text/plain` parts，非 text part 跳过并 warn；后续如需多模态再扩展 |
| 外部智能体与内部智能体 Timeline 不一致 | `ExternalExpertClient` 产出相同 `StreamToken`（step + content），Timeline 形态对前端透明 |
| 智能体 `data_scope` 工具不兼容 | 不强制所有工具支持；不支持的工具忽略（向后兼容）；后续 SDK 逐步适配 |

---

## 14. 检查门

### 14.1 Agent Team 核心（T）

| # | 场景 | 期望 |
|---|------|------|
| T1 | `$policy-agent $finance-agent 分析差旅报销合规性` | `team-convene` + 起步 agent 执行 + 至少一次委派 + Synthesizer 正文 |
| T2 | 智能体调 `delegate_to_agent` | 接收 agent 成为新 currentAgent；HandoffEnvelope 可见；TeamState 更新 |
| T3 | 智能体调 `finish_task` | Team 标记 COMPLETED；Synthesizer 汇总 |
| T4 | 循环委派 A->B->A->B | 循环检测触发强制 synthesize；无死循环 |
| T5 | 超过 maxHandoffs | 强制 synthesize；Timeline 无异常 |
| T6 | HandoffEnvelope 敏感信息过滤 | envelope 无 userId/tenantId/原始支付数据 |
| T7 | 权限不足委派 | allowedTools 裁剪；接收 agent 无权工具时拒绝或降级 |
| T8 | TeamState 共享 | 多个 agent 读写同一 teamId 的 confirmedFacts/tasks |
| T9 | Timeline 委派箭头 | 前端显示 A->B 委派连线；可展开交接包 |
| T10 | peer-collab 代码零残留 | grep 无 ExpertHubEngine/ExpertSpeak*/PeerMsgSupport |

### 14.2 智能体定义模型扩展（C）

| # | 场景 | 期望 |
|---|------|------|
| C1 | 智能体 `kb_scope` 生效 | 法务智能体 `kb_scope=["kb-legal"]` 时 `search_knowledge` 仅检索法务库，不查 HR 库 |
| C2 | 智能体 `tenant_id` 隔离 | A 租户用户组队时看不到 B 租户私有智能体；default 智能体全可见 |
| C3 | 智能体 `permissions` 生效 | `toolConfirmation=always` 的智能体调写工具时 HITL 开启；`sandboxWriteMode=never` 禁止沙箱写 |
| C4 | 智能体 `data_scope` 透传 | 工具收到 `ToolAuditContext.dataScope`；业务工具按范围过滤（支持的工具） |
| C5 | 智能体 `model_config` 生效 | 配 `model=gpt-4o` 的智能体实际用 gpt-4o（非全局默认） |
| C6 | Handoff 交接按权限裁剪 | delegate 时 `allowedTools` = 发起方 ∩ 接收方 tools；`dataScope` 取交集 |

### 14.3 ReAct 调用智能体（R）

| # | 场景 | 期望 |
|---|------|------|
| R1 | ReAct 主 Agent 调 `spawn_subagent(expertId="policy-expert", prompt="检索差旅住宿标准并返回要点")` | 主卡 `subagent-{runId}` + label=制度智能体；抽屉 `spawnPrompt` + `subSteps`；子有 think/tool；终态文本回主 |
| R2 | `spawn_subagent(prompt="...")` 无 expertId | 现有行为不变（`mode-overlay.subagent`，无 skill） |
| R3 | `expertId` 不存在 | 报错进 tool result；无子卡 |
| R4 | 智能体工具白名单生效 | 智能体子 Agent 仅可调用 `tools_json` 内工具 + `search_knowledge` + sandbox |
| R5 | 智能体子 Agent HITL | 抽屉内确认 -> 续跑；主卡 `待确认`->`运行中` |

### 14.4 外部智能体 A2A 接入（X）

| # | 场景 | 期望 |
|---|------|------|
| X1 | `/agents` 外部 tab 新增：填 agentCard URL | 拉取 Agent Card 预填 name/description/tags；存库 source=EXTERNAL |
| X2 | `$external-legal 分析这份合同风险` | `team-convene` + `agent-external-legal-h1` 步；发言经 A2A artifact 流式；Synthesizer 汇总 |
| X3 | ReAct `spawn_subagent(expertId="external-legal", prompt="审查条款3")` | 主卡 subagent + label=外部法务智能体；A2A task 流式回 tool result |
| X4 | 外部智能体超时/不可达 | tool result 含错误；Team 中该智能体步标失败，其他不受影响 |
| X5 | 外部智能体 INPUT_REQUIRED（HITL） | 智能体步 `待确认`；用户确认后续跑（A2A `tasks/send` 续传） |
| X6 | 内部+外部智能体混合 `$policy-agent $external-legal` | 两智能体均发言；Timeline 分别标 INTERNAL/EXTERNAL badge |

### 14.5 Handoff 交接 + 安全缺口（H）

| # | 场景 | 期望 |
|---|------|------|
| H1 | `$A $B 协作`：A 发言后 B 接手 | B 收到 `HandoffEnvelope`（非裸 transcript）；B 的 `injectedBlocks` 含结构化字段 |
| H2 | 交接包含敏感字段 | envelope 无 userId/tenantId/原始支付数据；`evidenceRefs` 已脱敏 |
| H3 | A 发言含错误猜测 | `confirmedFacts` 标 `verified=false`；B 不将其当事实继承 |
| H4 | B 权限不足（缺 A 用的写工具） | B 拒绝接手或请求重新授权；不猜测 |
| H5 | 完整历史可追溯 | TeamState trace 含完整记录；B 可按需查询 |
| H6 | 智能体层写工具 HITL | `permissions.toolConfirmation` 控制开关；确认需校验发起用户身份 |
| H7 | 工具 output 脱敏 | 审计记录的 output 经 `DesensitizeClient.scrub` |
| H8 | 审计查询鉴权 | `/api/audit/*` 校验 conversationId/userId 归属 |

Live：新增 `scripts/verify_agent_team_live.py`（T1-T10 + C1-C6）+ `scripts/verify_external_agent_live.py`（X1-X6，需 mock A2A server）+ `scripts/verify_handoff_live.py`（H1-H8）

---

## 15. 后端代码全量重命名（Expert/Peer -> Agent/Team）

原 spec 顾虑 `Expert->Agent` 与现有 `AgentRuntime`/`AgentRunRequest` 命名冲突，经核查为**伪命题**：`AgentRuntime` 在 `com.sunshine.orchestrator.agent` 包，存活类在 `catalog`/`routing`/新 `team` 包，不冲突；expert-manager 的 `com.sunshine.expert` -> `com.sunshine.agent` 是独立服务包路径，不冲突。

### 15.1 重命名映射总表

#### 15.1.1 服务 / 包 / DB

| 旧 | 新 | 说明 |
|----|-----|------|
| `expert-manager` 服务 | `agent-manager` | Spring 服务名 + Nacos 注册名 |
| `sunshine_expert` DB | `sunshine_agent` | MySQL 库名 |
| `expert_definition` 表 | `agent_definition` | 含全部扩展字段 |
| `expert_skill_link` 表 | `agent_skill_link` | |
| `com.sunshine.expert` 包 | `com.sunshine.agent` | expert-manager 全部 Java |
| `com.sunshine.orchestrator.expert` 包 | `com.sunshine.orchestrator.team` | orchestrator 侧（存活类移入） |
| `com.sunshine.orchestrator.peer` 包 | **删除** | Peer 全套删除，不重命名 |
| `sunshine-expert-manager.yaml` | `sunshine-agent-manager.yaml` | Nacos 配置文件 |
| `15-sunshine-expert-manager.sql` | `15-sunshine-agent-manager.sql` | MySQL init SQL |

#### 15.1.2 Java 类名（存活类重命名）

| 旧类名 | 新类名 | 所在模块 |
|--------|--------|----------|
| `ExpertManagerApplication` | `AgentManagerApplication` | agent-manager |
| `ExpertDefinitionEntity` | `AgentDefinitionEntity` | agent-manager |
| `ExpertSkillLinkEntity` | `AgentSkillLinkEntity` | agent-manager |
| `ExpertSkillLinkId` | `AgentSkillLinkId` | agent-manager |
| `ExpertAdminService` | `AgentAdminService` | agent-manager |
| `ExpertCatalogRegistry` | `AgentCatalogRegistry` | agent-manager |
| `ExpertCreateRequest` | `AgentCreateRequest` | agent-manager |
| `ExpertUpdateRequest` | `AgentUpdateRequest` | agent-manager |
| `ExpertEnableRequest` | `AgentEnableRequest` | agent-manager |
| `ExpertCatalogIndexEntry` | `AgentCatalogIndexEntry` | agent-manager + orchestrator |
| `ExpertCatalogEntry` | `AgentCatalogEntry` | agent-manager + orchestrator |
| `ExpertSkillLinkRepository` | `AgentSkillLinkRepository` | agent-manager |
| `ExpertDefinitionRepository` | `AgentDefinitionRepository` | agent-manager |
| `ExpertAdminController` | `AgentAdminController` | agent-manager |
| `ExpertCatalogController` | `AgentCatalogController` | agent-manager |
| `ExpertErrorCode` | `AgentErrorCode` | agent-manager |
| `ExpertCatalogService` | `AgentCatalogService` | orchestrator |
| `ExpertCatalogClient` | `AgentCatalogClient` | orchestrator |
| `ExpertBindingParser` | `AgentBindingParser` | orchestrator |
| `ExpertBindingRoutingPolicy` | `AgentBindingRoutingPolicy` | orchestrator |
| `ExpertBindingOutcome` | `AgentBindingOutcome` | orchestrator |
| `ExpertBindingSource` | `AgentBindingSource` | orchestrator |
| `ExpertToolsJson` | `AgentToolsJson` | orchestrator |
| `ExpertStepLabels` | `TeamStepLabels` | orchestrator |
| `ExpertTimelineSupport` | `TeamTimelineBridge` | orchestrator（重构） |
| `ExpertTranscriptEntry` | `TeamHandoffRecord` | orchestrator（重构） |
| `ExpertCollaborationPlanSanitizer` | `TeamPlanSanitizer` | orchestrator |
| `ExpertCoordinatorProperties` | `TeamCoordinatorProperties` | orchestrator |
| `ExpertsController` | `AgentsController` | bff |
| `ExpertManagerClient` | `AgentManagerClient` | bff |

#### 15.1.3 Java 类名（删除类，不重命名直接删）

```
ExpertHubEngine / ExpertSpeakStreamer / ExpertSpeakHook / ExpertPeerAgentFactory
ExpertRoundCoordinatorService / ExpertCoordinatorService / ExpertConsultationExecutor
ExpertSessionRounds / ExpertContinueDecision / ExpertSpeakCallback / ExpertCollaborationParams
ExpertRoster / ConsultationSynthesizer
PeerSynthesisProperties / PeerStepLabels / PeerTranscriptEntry / PeerRunRepository
PeerRunAuditView / PeerMsgSupport / PeerRunAuditService / PeerRunEntity
```

#### 15.1.4 前端

| 旧 | 新 |
|----|-----|
| `/experts` 路由 | `/agents` |
| `ExpertsView.vue` | `AgentsView.vue` |
| `PeerCollabPanel.vue` | `TeamStepPanel.vue`（重构） |
| `useChatExpertMention.ts` | `useChatAgentMention.ts` |
| `useExpertsRouteState.ts` | `useAgentsRouteState.ts` |
| `experts.ts`（API） | `agents.ts` |
| `peerAudit.ts`（API） | `teamAudit.ts` |
| `expertMention.ts` | `agentMention.ts` |
| 文案"专家" | "智能体" |
| 文案"多专家协作" | "多智能体协作" |

#### 15.1.5 Catalog ID

| 旧 | 新 | 说明 |
|----|-----|------|
| `peer.gather-instruction` | **删除** | Team 不用 gather |
| `peer.speak-prompt` | **删除** | speak 阶段合并 |
| `peer.synthesis-prompt` | `team.synthesis-prompt` | |
| `peer.round-continue-prompt` | **删除** | Team 无轮次 |
| `peer.round-speakers-prompt` | **删除** | Team 无选人轮次 |
| `expert.coordinator-prompt` | `team.start-agent-prompt` | 选起步 agent |
| `expert.complexity-prompt` | **删除** | 不再评估复杂度 |
| `timeline.steps.expert` | `timeline.steps.team-agent` | |
| `timeline.steps.expert-convene` | `timeline.steps.team-convene` | |
| Nacos `peer.synthesis` | Nacos `team.synthesis` | 非提示词运行参数 |

#### 15.1.6 脚本

| 旧 | 新 |
|----|-----|
| `verify_expert_consultation_live.py` | `verify_agent_team_live.py` |
| `verify_peer_collab_live.py` | `verify_agent_team_live.py`（合并） |
| `sync_enterprise_experts.py` | `sync_enterprise_agents.py` |

#### 15.1.7 网关 / BFF 路由

| 旧 | 新 |
|----|-----|
| `health-expert-manager` | `health-agent-manager` |
| `/api/experts/**` | `/api/agents/**` |
| BFF `expert-manager` 转发 | BFF `agent-manager` 转发 |

### 15.2 重命名策略

**不做 DB 迁移脚本**（CLAUDE.md 约定禁止 Flyway，SSOT 在 `docker/mysql/init/`）。重命名方式：

1. **新环境**：直接用新 SQL 文件 `15-sunshine-agent-manager.sql`（表名 `agent_definition` / `agent_skill_link`）
2. **已有环境**：提供一次性 `ALTER TABLE RENAME` SQL（手动执行，不进 init）
3. **服务名变更**：Nacos 注册名 + 网关路由 + BFF 转发同步改，需重启

### 15.3 重命名与重构的执行顺序

重命名与 Agent Team 重构**合并执行**，不分两步（避免改两遍）：

```
1. DB: 新建 15-sunshine-agent-manager.sql（新表名 + 全部扩展字段）
2. expert-manager: com.sunshine.expert -> com.sunshine.agent（全量重命名包+类）
3. orchestrator: 删除 peer/ 包全量 + expert/ 包中删除类
4. orchestrator: expert/ 存活类重命名 + 移入 team/ 包
5. orchestrator: 新增 TeamOrchestrator / TeamStateService / DelegateToAgentTool 等
6. bff: ExpertsController -> AgentsController + 路由改
7. 前端: /experts -> /agents + 组件重命名 + 术语改
8. Catalog: SQL init 改 ID + Nacos 改 key
9. 脚本: 重命名
10. 网关/Nacos: 服务名 + 路由改
```

### 15.4 命名冲突排查（已确认无冲突）

| 已有 Agent* 类 | 包路径 | 新 Agent* 类 | 包路径 | 冲突？ |
|----------------|--------|-------------|--------|--------|
| `AgentRuntime` | `orchestrator.agent` | `AgentCatalogService` | `orchestrator.catalog` | 否 |
| `AgentRunRequest` | `orchestrator.agent` | `AgentDefinitionEntity` | `agent-manager`（独立服务） | 否 |
| `AgentRole` | `orchestrator.agent` | `AgentAdminController` | `agent-manager` | 否 |
| `AgentRunRequest` | `orchestrator.agent` | `AgentBindingParser` | `orchestrator.routing` | 否 |

结论：`orchestrator.agent` 包（运行时）与 `agent-manager` 服务 / `orchestrator.catalog` / `orchestrator.routing` / `orchestrator.team` 是不同包路径，**全量重命名无冲突**。

---

## 16. 实施衔接

### 16.1 与现有设计的关系

| 现有设计 | 关系 |
|----------|------|
| `2026-07-07-expert-consultation-design.md` | **被本设计取代**（peer-collab -> agent-team） |
| `2026-07-18-react-spawn-subagent-design.md` | 保留（spawn_subagent 与 Team 正交，不删） |
| `2026-07-24-expert-as-subagent-design.md` | **被本设计合并**（内部统一 + 外部 A2A + Handoff 全部并入本文档） |
| `2026-07-28-agent-team-design.md` | **被本设计合并**（Agent Team + 智能体定义模型扩展并入本文档） |
| AS2 P6（peer-collab 正式化） | E5 已否决迁移；本设计替代 P6 的 peer-collab 重构目标 |

### 16.2 任务拆解

| 任务 | 说明 | 阶段 |
|------|------|------|
| `agent_definition` DDL 新建 | 新 SQL `15-sunshine-agent-manager.sql`：`agent_definition` / `agent_skill_link` + 全部扩展字段 | 基础设施 |
| `AgentCatalogEntry` 新建 | DTO 含全部扩展字段（agent-manager + orchestrator 两份） | 基础设施 |
| `AgentRunRequest` 扩展 | 加 `kbScope` / `dataScopeJson` / `permissionsJson` / `modelConfigJson` / `maxHandoffs` 透传 | 基础设施 |
| 安全缺口修复 | HITL 身份校验 / output 脱敏 / 审计鉴权 / transcript 脱敏 | 安全（最高优） |
| HITL 动态化 | `bindHitlBridge` 按 `permissions.toolConfirmation` 决定（always/never/inherit） | 权限落地 |
| 沙箱 WriteMode 覆盖 | 按 `permissions.sandboxWriteMode` 覆盖 `SandboxWriteHitlMode` | 权限落地 |
| 模型配置覆盖 | `AgentRuntime` 读 `modelConfigJson` 覆盖 `OpenAIChatModel` | 权限落地 |
| RagTool kbScope | `resolveKbId` 优先读智能体 `kbScope`，覆盖会话级 kbId | 知识库范围 |
| dataScope 透传 | `ToolAuditContext` 加 `dataScope` / `kbScope` / `permissions` 字段 | 数据范围 |
| 后端全量重命名 | expert-manager -> agent-manager；Expert* -> Agent*/Team*；Peer* 删除；DB 表名；Nacos；网关（详见 §15） | 重命名 |
| 前端全量重命名 | `/experts`->`/agents`；组件名；文案"专家"->"智能体" | 重命名 |
| `TeamStateService` | Redis 读写 TeamState + TTL | Team 核心 |
| `TeamOrchestrator` | 创建 Team / 选起步 / 调度 loop / 死锁检测 / 强制收束 | Team 核心 |
| `TeamHandoffService` | 构建/裁剪/脱敏 HandoffEnvelope；按 `allowedTools` + `dataScope` 取交集裁剪 | Team 核心 |
| `DelegateToAgentTool` | 元工具：成员间委派 | Team 核心 |
| `FinishTaskTool` | 元工具：声明完成 | Team 核心 |
| `TeamSynthesizer` | TeamState -> 流式正文 | Team 核心 |
| `TeamTimelineBridge` | Team 步骤折叠 + 委派箭头 | Team 核心 |
| `TeamRouter` | 选起步 agent / NO_ACTION 时选下一个 | Team 核心 |
| `AgentExecutorRouter` | 按 source 分派 INTERNAL/EXTERNAL | 统一分派 |
| `ExternalAgentClient` | A2A Client：`tasks/sendSubscribe` + SSE 事件 -> StreamToken | 外部接入 |
| agent-manager API | 外部智能体 CRUD + Agent Card 拉取预填接口 | 外部接入 |
| `SpawnSubagentTool` 扩展 | 入参加 `agentId` + `resolveAgent`；走 `AgentExecutorRouter` | ReAct 集成 |
| 路由层改造 | `PEER_COLLAB` -> `AGENT_TEAM`；`$A $B` 进 Team | 路由 |
| 删除 peer-collab 全套 | ExpertHubEngine/ExpertSpeak*/PeerMsg*/ExpertRoundCoordinator/ConsultationSynthesizer | 清理 |
| Catalog 新增 | team.* 系列；废弃 peer.*/expert.* 协作专属 | 提示词 |
| 前端 | `/agents` 配置页扩展 + TeamStepPanel + 委派箭头 + 外部 tab | UI |
| Live | T1-T10 + C1-C6 + R1-R5 + X1-X6 + H1-H8 检查门 | 验收 |

### 16.3 优先级

1. **`agent_definition` DDL 新建 + DTO + AgentRunRequest 扩展 + 后端全量重命名** -- 基础设施 + 重命名合并执行，其他任务依赖
2. **安全缺口修复**（HITL 身份校验/output 脱敏/审计鉴权）-- 影响线上安全
3. **HITL 动态化 + 沙箱 WriteMode 覆盖 + 模型配置** -- `permissions_json` / `model_config_json` 落地
4. **RagTool kbScope + dataScope 透传** -- 知识库范围/数据范围生效
5. **前端全量重命名** -- 低风险，可并行
6. **内部智能体统一执行内核** -- 删 `ExpertPeerAgentFactory` / `ExpertSpeakHook` / `ExpertSpeakStreamer`（重命名时一并删）
7. **Agent Team 核心**（TeamOrchestrator + delegate + TeamState + AgentExecutorRouter）-- 主体重构
8. **peer-collab 删除** -- Team 稳定后删除
9. **外部 A2A 接入**（ExternalAgentClient + 前端外部 tab）-- 作为 Team 外部成员的扩展
10. **spawn_subagent agentId 集成** -- ReAct 调用预定义智能体

---

## 17. 自检清单

- [x] 无 TBD/TODO 占位需求
- [x] 统一覆盖：内部统一执行内核 + 外部 A2A 接入 + Handoff 交接 + Agent Team 去中心化协作 + 智能体定义模型扩展
- [x] 全量重命名：Expert->Agent / Peer->Team（代码 + DB + 服务名 + 前端 + Catalog，命名冲突已排查无冲突）
- [x] 内部/外部在 `ExpertCatalogEntry` 层统一契约，执行层按 `source` 分派
- [x] A2A 仅做 Client（消费外部 Agent Card），不做 Server
- [x] 去中心化控制模式：智能体自主委派（delegate_to_agent）
- [x] 完全替换 peer-collab：删除 ExpertHubEngine 全套
- [x] 共享状态：TeamState（Redis）区别于单 Agent 私有 AssembledContext
- [x] HandoffEnvelope：结构化交接包替代裸 contextBlocks；从 Hub 注入改为智能体主动产出
- [x] 三类不可乱传信息有明确处理（敏感信息脱敏 / 噪声标记 verified / 责任边界 allowedTools）
- [x] 权限不足拒绝而非猜测
- [x] 与 spawn_subagent 正交：主子（中心化）vs Team（去中心化），两者共存
- [x] 死锁/收束保护：maxHandoffs + 循环检测 + 超时 + 强制 synthesize
- [x] 智能体定义模型扩展：tenant_id / kb_scope / data_scope / permissions / model_config / max_iters / max_handoffs
- [x] 知识库范围：kb_scope_json 覆盖会话级 kbId，RagTool 按智能体裁剪
- [x] 权限模型：permissions_json 覆盖 HITL/沙箱 WriteMode/委派权限
- [x] 数据访问范围：data_scope_json 透传到工具层
- [x] 安全缺口（HITL/脱敏/鉴权）与 Handoff 一并修复
- [x] 不做兼容兜底（旧两阶段直接删，非 flag 切换）
- [x] 范围可落单一实施计划
