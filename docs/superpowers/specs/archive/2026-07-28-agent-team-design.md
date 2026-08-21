# Agent Team 去中心化协作 - 技术设计

> **⚠️ 已被取代**：本文档内容已合并入 [`2026-07-29-multi-agent-unified-design.md`](../2026-07-29-multi-agent-unified-design.md)。保留仅供历史追溯，请以统一设计文档为准。

> **状态**：**→ 已归档** · Agent Team 去中心化方案**被否决**（理由见 multi-agent-unified §1.3；外部 A2A 无法参与 delegate/TeamState/Handoff）
> **日期**：2026-07-28
> **编号**：阶段四增量（重构 peer-collab -> agent-team）
> **前置**：[多专家协作](./2026-07-07-expert-consultation-design.md) · [4.7.6 spawn_subagent](./2026-07-18-react-spawn-subagent-design.md) · [专家作为子 Agent + Handoff](./2026-07-24-expert-as-subagent-design.md)
> **一句话**：将「多专家协作」（Hub 固定轮次广播）重构为「Agent Team」（去中心化动态委派 + 共享状态 + Handoff 接力）；智能体自主决定下一步交给谁，完全替换 peer-collab。

---

## 1. 背景与问题

### 1.1 当前 peer-collab 的本质局限

当前 `ExpertHubEngine.run()` 的调度循环是**固定轮次 + 顺序广播**：

```
for round = 1..effectiveMax:
    speakers = resolveSpeakers(roster, ...)     // 全员或反应式选人
    for expert in speakers:                      // 顺序逐个发言
        reply = invokeAgent(expert, contextBlocks)  // 收到完整历史
        contextBlocks.add(reply)                 // 全量累积
    if evaluateContinue(...) == false: break
```

本质是「多视角咨询」而非「团队协作」：每个智能体对同一问题独立发言，彼此无任务交接、无动态委派。

### 1.2 与 Agent Team 的本质差距

| 维度 | 当前 peer-collab | Agent Team（目标） |
|------|------------------|---------------------|
| 控制模式 | Hub 固定轮次编排，智能体被动发言 | 去中心化，智能体自主决定"下一步找谁" |
| 通信机制 | 单向累积（contextBlocks 全量追加） | 点对点直接委派 + 共享状态 |
| 上下文管理 | 全量历史裸传（所有人看所有发言） | 共享 Team State + HandoffEnvelope 结构化交接 + 按需查询 trace |
| 协作关系 | "轮流表态"（每个人对同一问题发言） | "动态接力"（根据意图委派给最合适的下一个智能体） |
| 适用场景 | 多角度观点汇总（Synthesizer 就够） | 强依赖多步骤迭代配合（软件开发/复杂策划/长流程审批） |

### 1.3 决策

- **控制模式**：去中心化 -- 每个智能体可自主决定下一步交给谁
- **peer-collab 去向**：完全替换 -- Agent Team 覆盖所有协作场景，删除 peer-collab 代码
- **框架依赖**：AgentScope 2.0 无原生多智能体协作原语（MsgHub 已删），Agent Team 全自研，复用已有 `AgentRuntime.run` + `AgentRunRequest.sub`

---

## 2. 智能体定义模型扩展

当前 `expert_definition` 表仅有 `display_name / description / system_prompt / enabled / tags_json / tools_json`，无法支撑 Agent Team 的 Handoff 权限裁剪、知识库范围、租户隔离等需求。需扩展。

### 2.1 现状缺口

| 维度 | 现状 | 问题 |
|------|------|------|
| 知识库范围 | 无配置；所有 Agent 共享会话级单一 `kbId`（`ChatConversationEntity.kbId`） | 不同智能体应访问不同知识库（如法务智能体只查法务库），无法裁剪 |
| 租户绑定 | `expert_definition` 无 `tenantId`；专家全局可见 | 跨租户场景下智能体应按租户隔离（A 租户的法务智能体 != B 租户的） |
| 权限模型 | 工具仅 `require_confirmation` + `side_effect`；沙箱策略硬编码 | 智能体需要更细粒度的数据访问权限（只读/可写/数据范围） |
| 数据访问范围 | 完全不存在 `dataScope/kbScope` | 智能体调工具时无法限定可操作的数据范围（如只能查本部门报销） |
| 模型配置 | 无；所有智能体用同一 `OpenAIChatModel` | 不同智能体可能需要不同模型/温度（如法务用更强模型） |

### 2.2 扩展后的 `expert_definition` 表（DDL 增量）

```sql
ALTER TABLE expert_definition
    ADD COLUMN tenant_id         VARCHAR(32) NOT NULL DEFAULT 'default' AFTER enabled,
    ADD COLUMN kb_scope_json     VARCHAR(512) NOT NULL DEFAULT '[]' AFTER tools_json,
    ADD COLUMN data_scope_json   TEXT AFTER kb_scope_json,
    ADD COLUMN permissions_json  VARCHAR(512) NOT NULL DEFAULT '{}' AFTER data_scope_json,
    ADD COLUMN model_config_json VARCHAR(512) NOT NULL DEFAULT '{}' AFTER permissions_json,
    ADD COLUMN max_iters         INT NOT NULL DEFAULT 2 AFTER model_config_json,
    ADD COLUMN max_handoffs      INT NOT NULL DEFAULT 5 AFTER max_iters,
    ADD INDEX idx_tenant_enabled (tenant_id, enabled);
```

### 2.3 新增字段说明

#### `tenant_id` -- 租户绑定

- 智能体按租户隔离：`tenant_id = 'default'` 为全局共享（现有种子）；租户私有智能体 `tenant_id = 具体租户`
- Catalog 查询按 `tenant_id = ? OR tenant_id = 'default'` 过滤（与工具可见性一致）
- Agent Team 组队时只选当前租户可见的智能体

#### `kb_scope_json` -- 知识库范围

```json
["kb-legal", "kb-hr-policy"]
```

- 空数组 `[]` = 继承会话级 `kbId`（现状，向后兼容）
- `["*"]` = 全部知识库
- 具体列表 = 仅可检索这些 kbId
- 运行时 `RagTool` 按 `kb_scope_json` 覆盖会话级 `kbId`：智能体调 `search_knowledge` 时，若 `kb_scope` 非空，用它替代会话 `kbId`

**改动点**：`RagTool.resolveKbId`（当前只读会话级 `ToolAuditContext.kbId`）增加从智能体配置读取 `kb_scope` 的逻辑；`AgentRunRequest` 增加 `kbScope` 字段透传。

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
  "toolConfirmation": "always",     // always / never / inherit（inherit = 读工具 require_confirmation）
  "sandboxWriteMode": "never",      // never / always / smart（覆盖 SandboxWriteHitlMode）
  "allowDelegate": true,            // 是否允许调 delegate_to_agent
  "allowFinishTask": true,          // 是否允许调 finish_task
  "maxConcurrentHandoffs": 3        // 同时被委派的次数上限
}
```

- `toolConfirmation` 覆盖当前专家层 HITL 硬关闭（`bindHitlBridge(..., false)`）的问题：按智能体配置决定
- `sandboxWriteMode` 覆盖沙箱策略：法务智能体可设 `never`（禁止写），开发智能体可设 `smart`

#### `model_config_json` -- 模型配置

```json
{
  "model": "gpt-4o",
  "temperature": 0.3
}
```

- 空 `{}` = 继承全局默认（现状）
- 非空 = 该智能体用指定模型/温度（`ExpertPeerAgentFactory` / `AgentRuntime` 读取并覆盖 `OpenAIChatModel.builder`）

#### `max_iters` / `max_handoffs` -- 执行限制

- `max_iters`：单次被委派时 ReAct 最大轮次（现有硬编码 2，改为可配置）
- `max_handoffs`：该智能体在单次 Team 协作中最多被委派几次（防止循环）

### 2.4 ExpertCatalogEntry 扩展

```java
public record ExpertCatalogEntry(
        String id,
        String displayName,
        String description,
        String systemPrompt,
        List<String> skillIds,
        List<String> tags,
        String toolsJson,
        boolean enabled,
        // 新增
        String tenantId,             // 租户绑定
        List<String> kbScope,        // 知识库范围
        String dataScopeJson,        // 数据访问范围
        String permissionsJson,      // 权限配置
        String modelConfigJson,      // 模型配置
        int maxIters,                // 单次 ReAct 上限
        int maxHandoffs,             // Team 内被委派上限
        // 外部 A2A（来自 expert-as-subagent spec）
        ExpertSource source,         // INTERNAL / EXTERNAL
        String agentCardUrl,
        String endpointOverride
) {
    public enum ExpertSource { INTERNAL, EXTERNAL }
}
```

### 2.5 运行时透传链路

```
expert_definition (DB)
  -> ExpertCatalogEntry (DTO)
  -> AgentRunRequest.sub(...) 新增字段：
       kbScope / dataScopeJson / permissionsJson / modelConfigJson / maxHandoffs
  -> ReActAgentRuntime：
       modelConfig -> OpenAIChatModel.builder 覆盖
       kbScope -> RagTool.resolveKbId 优先用 kbScope
       permissionsJson -> HITL bindHitlBridge 覆盖 / 沙箱 WriteMode 覆盖
       dataScopeJson -> StepEventBridge.ToolAuditContext 注入
  -> 工具执行：
       ToolAuditContext.dataScope -> 业务工具读取并过滤
       ToolAuditContext.kbScope -> RagTool 检索范围
```

### 2.6 前端 `/agents` 配置页扩展

| 配置区块 | 字段 |
|----------|------|
| 基础（现有） | ID / 展示名 / 描述 / systemPrompt / 启用 / tags |
| 工具与技能（现有） | toolsJson / skillIds |
| 知识库范围（新） | kbScope 多选（从 rag-service `/api/rag/kb/list` 拉） |
| 数据范围（新） | dataScope JSON 编辑器（结构化表单 or JSON） |
| 权限（新） | toolConfirmation 下拉 / sandboxWriteMode 下拉 / allowDelegate 开关 / allowFinishTask 开关 |
| 模型（新） | model 下拉 / temperature 滑块 |
| 执行限制（新） | maxIters / maxHandoffs 数值输入 |
| 租户（新） | tenantId（admin 可见，普通用户只看本租户） |
| 外部接入（A2A） | source / agentCardUrl / endpointOverride（外部 tab） |

---

## 3. 核心概念

### 3.1 Agent Team

一组智能体组成的协作团队，围绕一个用户目标动态协作。不预设轮次、不全员广播；由当前持有"发言权"的智能体决定下一步动作（继续处理 / 委派给队友 / 请求用户补充 / 完成任务）。

### 3.2 Team State（共享状态）

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
- 读写：每次智能体被委派时，读取当前 Team State 快照注入 context；发言后更新 Team State

### 3.3 HandoffEnvelope（交接包）

复用 `2026-07-24-expert-as-subagent-design.md` §9.5.2 的设计，但从"Hub 注入"改为"智能体主动产出"：

```java
public record HandoffEnvelope(
    String fromAgentId,             // 交接发起方
    String toAgentId,               // 接收方（null = 交回 Team/用户）
    String objective,               // 本次交接的子目标
    List<ConfirmedFact> facts,      // 已确认事实
    List<String> evidenceRefs,      // 证据链接
    List<AttemptedAction> attempted,// 已尝试动作
    List<String> openQuestions,     // 未解决问题
    List<String> allowedTools,      // 权限边界
    String expectedOutput,          // 期望输出
    HandoffReason reason            // DELEGATE / ESCALATE / HAND_BACK / COMPLETE
) {}
```

### 3.4 去中心化委派元工具

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
    // 6. 更新 Team State
}
```

与 `spawn_subagent` 的区别：`spawn_subagent` 是主 Agent -> 子 Agent（中心化，子无自主权）；`delegate_to_agent` 是团队成员 -> 团队成员（去中心化，对等委派）。

---

## 4. 架构

### 4.1 整体链路

```
用户问题 -> 意图路由 -> AGENT_TEAM 模式
  -> TeamOrchestrator.createTeam(roster, objective)
       -> 初始化 TeamState（Redis）
       -> 选初始 agent（LLM 按 objective 选最合适的起步 agent）
  -> TeamOrchestrator.run(startAgentId)
       loop:
         agentRuntime.run(currentAgent, teamState, handoffEnvelope)
           -> agent ReAct：调工具 / delegate_to_agent / finish_task
         if agent 调 delegate_to_agent:
           -> 接收 agent 成为新 currentAgent，带 HandoffEnvelope
           -> 回到 loop
         if agent 调 finish_task 或无后续委派:
           -> TeamSynthesizer 汇总 TeamState -> message.content 流式
           -> break
         if 超过 maxHandoffs / 超时 / 死锁检测:
           -> 强制收束 -> TeamSynthesizer
```

### 4.2 组件清单

| 组件 | 职责 | 替代 |
|------|------|------|
| `TeamOrchestrator` | 创建 Team / 选起步 agent / 调度 loop / 死锁检测 | `ExpertConsultationExecutor` |
| `TeamStateService` | Redis 读写 TeamState | （新） |
| `TeamHandoffService` | 构建/裁剪/脱敏 HandoffEnvelope | （新，复用 Handoff 设计） |
| `DelegateToAgentTool` | 元工具：成员间委派 | `ExpertSpeakStreamer`（删） |
| `FinishTaskTool` | 元工具：智能体声明任务完成 | （新） |
| `TeamSynthesizer` | 汇总 TeamState -> 流式正文 | `ConsultationSynthesizer`（改造） |
| `TeamTimelineBridge` | Team 协作步骤折叠进主时间线 | `ExpertTimelineSupport` |
| `AgentRuntime.run` | 统一执行内核（复用，不改） | 不变 |

### 4.3 删除清单（完全替换 peer-collab）

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

### 4.4 与 spawn_subagent / Handoff spec 的关系

| 能力 | spawn_subagent（保留） | Agent Team（新增） |
|------|----------------------|---------------------|
| 控制模式 | 中心化（主->子，子无自主权） | 去中心化（成员对等委派） |
| 上下文 | 主写 prompt，子隔离 | HandoffEnvelope + 共享 TeamState |
| 通信 | 单向返回值 | 双向（委派 + 返回 + 共享状态） |
| 适用 | 独立并行子任务 | 强依赖迭代配合 |

`2026-07-24-expert-as-subagent-design.md` 的 HandoffEnvelope 设计（§9.5）直接被本设计复用，但从"Hub 注入"改为"智能体主动产出"。术语重命名（§0）也复用。

---

## 5. 执行流程详解

### 5.1 Team 创建与起步

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

### 5.2 单次 Agent 执行（Team loop 一步）

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
  result = agentRuntime.run(request).blockLast()
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

### 5.3 死锁与收束保护

| 情况 | 处理 |
|------|------|
| 超过 maxHandoffs（默认 10） | 强制 synthesize |
| 同一 agent 连续被委派 3 次无进展 | 强制 synthesize |
| 循环检测（A->B->A->B） | 强制 synthesize |
| 超时（默认 300s） | 强制 synthesize |
| 所有 agent 都 NO_ACTION | synthesize |

### 5.4 TeamSynthesizer

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

## 6. Timeline / UI

### 6.1 主时间线步骤形态

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
- 不显示"第 N 轮"（peer-collab 也不显示，但本设计连轮次概念都没有）
- 每次委派显示**委派箭头**（谁->谁），可展开 HandoffEnvelope
- 智能体可被多次委派，Timeline 按委派顺序排列（id=`agent-{agentId}-h{handoffSeq}`）
- **无** `expert-convene` 改为 `team-convene`；**无** `expert-{id}-s{seq}` 改为 `agent-{id}-h{seq}`

### 6.2 Step ID

| id | 说明 |
|----|------|
| `team-convene` | 团队组建 |
| `agent-{agentId}-h{handoffSeq}` | 该智能体第几次被委派（仅 id，界面不展示 seq） |
| `team-synthesize` | 团队结论汇总（可选 Timeline 步，或直接进 message.content） |

`phase=team`；`metadata`: `agentId`, `displayName`, `handoffSeq`, `handoffFrom?`（委派方）, `handoffReason?`

### 6.3 前端改动

- 新增 `TeamStepPanel`：主行 `label` + `summary`；展开含 HandoffEnvelope（目标/事实/未解决问题/已尝试动作）
- 委派箭头：两个 agent 步之间显示 `->` 连线 + 可展开交接包
- `/agents` 页（术语重命名后）增加「团队协作」配置：哪些智能体可组队、默认 maxHandoffs/超时
- Chat `$A $B` 仍触发 Team 模式（路由不变，执行模式改为 AGENT_TEAM）

---

## 7. Catalog / 提示词

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

**废弃**（peer-collab 专属）：
- `peer.gather-instruction` / `peer.speak-prompt` / `peer.synthesis-prompt` / `peer.round-continue-prompt` / `peer.round-speakers-prompt` / `expert.coordinator-prompt` / `expert.complexity-prompt`
- `timeline.steps.expert` / `timeline.steps.expert-convene`

---

## 8. Handoff 交接原则（复用 + 演进）

复用 `2026-07-24-expert-as-subagent-design.md` §9.5 的全部设计，演进点：

| 维度 | Handoff spec（peer-collab 场景） | Agent Team（本设计） |
|------|----------------------------------|----------------------|
| 交接包产出方 | Hub 编排器构建 | **智能体自主产出**（delegate_to_agent 参数） |
| 交接对象 | 下一轮发言者（Hub 决定） | **智能体自主指定**（agentId 参数） |
| 共享状态 | 无（仅 contextBlocks 累积） | **TeamState 共享**（所有成员可读写） |
| 权限裁剪 | Hub 按 roster 求交 | 智能体声明 allowedTools，TeamHandoffService 校验 |
| trace 保留 | peer_run.transcript_json | team_state 快照序列化（Redis + 可选落库） |

三类不可乱传信息处理不变（敏感信息脱敏 / 噪声标记 verified / 责任边界 allowedTools）。安全缺口修复（HITL/脱敏/鉴权）一并落地。

---

## 9. 边界与非目标

**做**

- 去中心化 Agent Team：智能体自主委派 + 共享 TeamState + Handoff 接力
- 完全替换 peer-collab：删除 ExpertHubEngine 等全套 peer 代码
- 复用 AgentRuntime.run + AgentRunRequest.sub（统一执行内核）
- 复用 HandoffEnvelope 设计（从 Hub 注入改为智能体主动产出）
- 术语重命名（专家->智能体，多专家协作->多智能体协作）
- 安全缺口修复（HITL/脱敏/鉴权）

**不做**

- 真正的点对点实时通信（当前仍是"委派-执行-返回"的顺序模式，非并行实时消息总线；未来可扩展为事件驱动）
- 智能体并行执行（v1 顺序委派；并行委派可后续扩展为 `delegate_to_agents` 批量）
- 跨进程 Team（v1 同进程；外部智能体经 A2A 接入后可参与 Team）
- 删除 spawn_subagent（保留，与 Team 正交：spawn 是主子中心化，Team 是对等去中心化）

---

## 10. 风险与对策

| 风险 | 对策 |
|------|------|
| 智能体不委派也不完成（NO_ACTION 死循环） | maxHandoffs + 超时 + 循环检测 + 强制 synthesize |
| 智能体乱委派（A->B->A 循环） | 循环检测（最近 4 次委派出现重复模式即强制收束） |
| TeamState 膨胀（handoffLog 无限增长） | handoffLog 保留最近 20 条 + 摘要旧的 |
| 智能体委派给不在 roster 的 agent | delegate_to_agent 校验 agentId 在 roster 内 |
| 敏感信息经 TeamState 泄露给其他成员 | TeamHandoffService 脱敏 + allowedTools 权限裁剪 |
| 比 peer-collab 慢（多轮 LLM 委派决策） | 委派决策可缓存；maxHandoffs 上限控制；复杂场景才用 Team（简单问题走 ReAct） |
| 路由层误判（简单问题进了 Team） | 路由 L1/L3 须区分"需团队协作"vs"单 Agent 可解"；Team 是高成本模式 |

---

## 11. 检查门

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
| T11 | 智能体 `kb_scope` 生效 | 法务智能体 `kb_scope=["kb-legal"]` 时 `search_knowledge` 仅检索法务库，不查 HR 库 |
| T12 | 智能体 `tenant_id` 隔离 | A 租户用户组队时看不到 B 租户私有智能体；default 智能体全可见 |
| T13 | 智能体 `permissions` 生效 | `toolConfirmation=always` 的智能体调写工具时 HITL 开启；`sandboxWriteMode=never` 禁止沙箱写 |
| T14 | 智能体 `data_scope` 透传 | 工具收到 `ToolAuditContext.dataScope`；业务工具按范围过滤（支持的工具） |
| T15 | 智能体 `model_config` 生效 | 配 `model=gpt-4o` 的智能体实际用 gpt-4o（非全局默认） |
| T16 | Handoff 交接按权限裁剪 | delegate 时 `allowedTools` = 发起方 ∩ 接收方 tools；`dataScope` 取交集 |

Live：新增 `scripts/verify_agent_team_live.py`

---

## 12. 实施衔接

### 12.1 与现有设计的关系

| 现有设计 | 关系 |
|----------|------|
| `2026-07-24-expert-as-subagent-design.md` | HandoffEnvelope §9.5 复用；术语重命名 §0 复用；外部 A2A §6 可作为 Team 外部成员接入的未来扩展 |
| `2026-07-07-expert-consultation-design.md` | **被本设计取代**（peer-collab -> agent-team） |
| `2026-07-18-react-spawn-subagent-design.md` | 保留（spawn_subagent 与 Team 正交，不删） |
| AS2 P6（peer-collab 正式化） | E5 已否决迁移；本设计替代 P6 的 peer-collab 重构目标 |

### 12.2 任务拆解

| 任务 | 说明 |
|------|------|
| `expert_definition` DDL 扩展 | 加 `tenant_id` / `kb_scope_json` / `data_scope_json` / `permissions_json` / `model_config_json` / `max_iters` / `max_handoffs` |
| `ExpertCatalogEntry` 扩展 | DTO 加对应字段（orchestrator + expert-manager 两份同步） |
| `AgentRunRequest` 扩展 | 加 `kbScope` / `dataScopeJson` / `permissionsJson` / `modelConfigJson` / `maxHandoffs` 透传 |
| `RagTool` 改造 | `resolveKbId` 优先读智能体 `kbScope`，覆盖会话级 kbId |
| `ToolAuditContext` 扩展 | 加 `dataScope` / `kbScope` / `permissions` 字段 |
| HITL 动态化 | `bindHitlBridge` 按 `permissions.toolConfirmation` 决定（always/never/inherit） |
| 沙箱 WriteMode 覆盖 | 按 `permissions.sandboxWriteMode` 覆盖 `SandboxWriteHitlMode` |
| 模型配置覆盖 | `ReActAgentFactory` / `ExpertPeerAgentFactory` 读 `modelConfigJson` 覆盖 `OpenAIChatModel` |
| `TeamStateService` | Redis 读写 TeamState + TTL |
| `TeamOrchestrator` | 创建 Team / 选起步 / 调度 loop / 死锁检测 / 强制收束 |
| `TeamHandoffService` | 构建/裁剪/脱敏 HandoffEnvelope；按 `allowedTools` + `dataScope` 取交集裁剪 |
| `DelegateToAgentTool` | 元工具：成员间委派 |
| `FinishTaskTool` | 元工具：声明完成 |
| `TeamSynthesizer` | TeamState -> 流式正文 |
| `TeamTimelineBridge` | Team 步骤折叠 + 委派箭头 |
| `TeamRouter` | 选起步 agent / NO_ACTION 时选下一个 |
| 路由层改造 | `PEER_COLLAB` -> `AGENT_TEAM`；`$A $B` 进 Team |
| 删除 peer-collab 全套 | ExpertHubEngine/ExpertSpeak*/PeerMsg*/ExpertRoundCoordinator/ConsultationSynthesizer(改造) |
| Catalog 新增 | team.* 系列；废弃 peer.*/expert.* 协作专属 |
| 前端 | `/agents` 配置页扩展（kb/data/permissions/model/执行限制）+ TeamStepPanel + 委派箭头 + 术语重命名 |
| 安全缺口修复 | HITL 身份校验 / output 脱敏 / 审计鉴权 / transcript 脱敏 |
| Live | T1-T16 检查门 |

### 12.3 优先级

1. **`expert_definition` DDL 扩展 + DTO 扩展** -- 基础设施，其他任务依赖
2. **安全缺口修复**（HITL/脱敏/鉴权）-- 影响线上安全
3. **HITL 动态化 + 沙箱 WriteMode 覆盖 + 模型配置** -- `permissions_json` / `model_config_json` 落地
4. **RagTool kbScope + dataScope 透传** -- 知识库范围/数据范围生效
5. **术语重命名**（前端）-- 低风险，可并行
6. **Agent Team 核心**（TeamOrchestrator + delegate + TeamState）-- 主体重构
7. **peer-collab 删除** -- Team 稳定后删除
8. **外部 A2A 接入** -- 作为 Team 外部成员的未来扩展

---

## 13. 自检清单

- [x] 无 TBD/TODO 占位需求
- [x] 去中心化控制模式：智能体自主委派（delegate_to_agent）
- [x] 完全替换 peer-collab：删除 ExpertHubEngine 全套
- [x] 共享状态：TeamState（Redis）区别于单 Agent 私有 AssembledContext
- [x] HandoffEnvelope 复用：从 Hub 注入改为智能体主动产出
- [x] 与 spawn_subagent 正交：主子（中心化）vs Team（去中心化），两者共存
- [x] 死锁/收束保护：maxHandoffs + 循环检测 + 超时 + 强制 synthesize
- [x] 智能体定义模型扩展：tenant_id / kb_scope / data_scope / permissions / model_config / max_iters / max_handoffs
- [x] 知识库范围：kb_scope_json 覆盖会话级 kbId，RagTool 按智能体裁剪
- [x] 权限模型：permissions_json 覆盖 HITL/沙箱 WriteMode/委派权限
- [x] 数据访问范围：data_scope_json 透传到工具层
- [x] 安全缺口一并修复
- [x] 术语重命名复用
- [x] 范围可落单一实施计划
