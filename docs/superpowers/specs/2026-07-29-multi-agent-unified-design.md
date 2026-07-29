# 多智能体协作统一设计 - 技术设计

> **状态**：修订稿（删除 Agent Team，改为 spawn_subagent 中心化协作）  
> **日期**：2026-07-29（修订版）  
> **编号**：阶段四增量（统一智能体定义 + A2A 外部接入 + spawn_subagent 多智能体协作）  
> **前置**：[4.7.6 spawn_subagent](./2026-07-18-react-spawn-subagent-design.md) · [多专家协作（原设计）](./2026-07-07-expert-consultation-design.md) · [A2A Protocol v1.0](https://github.com/a2aproject/A2A)  
> **一句话**：将「专家」统一为「智能体」概念，内部走 `AgentRuntime.run` 统一内核、外部走 A2A 接入；多智能体协作统一为 **spawn_subagent(expertId) 中心化编排**（主 Agent 有全局视角，可并行 spawn、综合结论），替代 peer-collab 的 Hub 固定轮次和 Agent Team 的去中心化委派；同步扩展智能体定义模型（租户/知识库/权限/数据范围）。  
> **修订理由**：Agent Team 去中心化方案引入 10+ 新组件（TeamOrchestrator/TeamState/HandoffEnvelope/delegate/RosterManager 等），但外部智能体（A2A fire-and-forget 语义）无法参与 Team 的 delegate/TeamState/Handoff 机制；路由层无法在 t=0 可靠判断「是否需要团队协作」；spawn_subagent(expertId) 已覆盖同等协作能力且组件零新增、主 Agent 全局视角、支持并行、无死锁风险。

> **本文档合并并取代以下 spec**：  
> - ~~`2026-07-24-expert-as-subagent-design.md`~~（内部统一 + 外部 A2A，被本文档合并）  
> - ~~`2026-07-28-agent-team-design.md`~~（Agent Team 去中心化，**被否决**，理由见 §1.3）  

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

2. **专家协作是「轮流表态」而非「协作」**：`ExpertHubEngine.run()` 是固定轮次 + 顺序广播——每个智能体对同一问题独立发言，`contextBlocks` 全量累积，彼此无任务交接。本质是「多视角咨询」，缺乏主 Agent 的全局控制与综合决策。

3. **智能体定义配置不足**：`expert_definition` 表仅有描述/工具/skills，无租户隔离、知识库范围、权限模型、数据访问范围。

### 1.3 为什么不用 Agent Team（去中心化委派）

Agent Team 方案（`2026-07-28-agent-team-design.md`）设计了去中心化动态委派（`delegate_to_agent`）+ 共享 TeamState + Handoff 交接。经评审**否决**，原因：

| 问题 | 说明 |
|------|------|
| 外部智能体无法参与 | A2A 是 fire-and-forget 语义：接收 query -> 执行 -> 返回结果。无法调用 `delegate_to_agent` / 读写 TeamState / 理解 HandoffEnvelope。Team 对外部智能体只能是"哑终端"，与 `spawn_subagent` 完全等价 |
| 路由层无法可靠判断"需要团队协作" | 是否需要多智能体是执行后才能知道的事。路由层在 t=0 只有一句话，无法判断单 Agent 能否解决。误判进 Team 导致高成本模式滥用 |
| 组件膨胀 | 需新建 TeamOrchestrator / TeamStateService / TeamHandoffService / RosterManager / DelegateToAgentTool / FinishTaskTool / TeamSynthesizer / TeamTimelineBridge / TeamRouter 等 10+ 组件 |
| 去中心化的实际收益有限 | 主 Agent 中心化控制有全局视角、可并行 spawn 多个子 Agent、可综合结论；去中心化委派链是串行的（A->B->C），延迟更高且引入死锁/循环风险 |
| 与 spawn_subagent 高度重复 | spawn_subagent(expertId) 已能覆盖多智能体协作场景，且已有组件、已有 Timeline、已有取消机制 |

**结论**：多智能体协作统一为 **spawn_subagent(expertId) 中心化编排**。主 Agent（ReAct MAIN）拥有全局视角，在执行中自主决定何时 spawn 哪个智能体，可并行 spawn 多个，综合结论后输出终态正文。外部/内部智能体在 spawn 层面完全平等。

### 1.4 决策

- **协作模式**：中心化——主 Agent 通过 `spawn_subagent(expertId)` 编排子智能体，主 Agent 有全局控制权和综合决策权
- **peer-collab 去向**：完全替换——`spawn_subagent(expertId)` 覆盖所有多智能体协作场景，删除 peer-collab 代码
- **框架依赖**：AgentScope 2.0 无原生多智能体协作原语（MsgHub 已删），协作全自研，复用已有 `AgentRuntime.run` + `AgentRunRequest.sub`
- **外部接入**：A2A Client 接入外部智能体，与内部智能体在 Catalog 层统一契约，在 spawn 层面完全平等

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

### 2.4 多智能体协作：spawn_subagent(expertId)

将 peer-collab 替换为 spawn_subagent(expertId) 中心化编排：主 Agent（ReAct MAIN）执行中自主决定 spawn 哪个预定义智能体，子智能体执行完毕返回结果，主 Agent 综合后输出终态正文。支持并行 spawn 多个智能体。外部/内部智能体在 spawn 层面完全平等。

### 2.5 不采纳的方案

- **方案 C（薄桥，不改专家执行内核）**：最小改动但留下"专家两套执行路径"的债，违反「禁止兼容旧行为兜底」。
- **AS2 P6 peer-collab 迁移 HarnessAgent**：E5 评审否决（2026-07-25），peer-collab 保留全栈自研不迁移官方 Subagent；本设计直接替换而非迁移。

**结论：方案 A（内部统一）+ B（外部 A2A）+ spawn_subagent 中心化协作（替代 Agent Team）。**

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

### 4.1 多智能体协作（spawn_subagent 中心化）

多智能体协作 = 主 Agent（ReAct MAIN）通过 `spawn_subagent(expertId)` 编排一个或多个预定义智能体。主 Agent 拥有全局视角：决定何时 spawn、spawn 谁、传入什么上下文、如何综合子智能体的返回结果。

**与 peer-collab 的区别**：
- peer-collab：Hub 固定轮次广播，每个智能体被动发言，无主 Agent 综合决策
- spawn 中心化：主 Agent 主动决定 spawn 时机和目标，子智能体执行完毕返回结果，主 Agent 综合结论

**触发路径**：

| 场景 | 机制 | 说明 |
|------|------|------|
| `$A $B` 显式绑定 | L0 `$` 路由 -> 主 Agent = A -> A 自主 spawn B | 用户指定协作对象，主 Agent 决定如何使用 |
| 智能体自主拉人 | 主 Agent think 发现需要其他领域视角 -> spawn_subagent(expertId) | 路由层无需判断"是否需要协作"，智能体执行中自主决定 |
| 外部智能体 | 同上，spawn_subagent(expertId) -> source=EXTERNAL -> A2A | 外部/内部在 spawn 层面完全平等 |

**并行 spawn**：主 Agent 可在同一轮发起多个 `spawn_subagent` 调用（互不依赖时），平台并行执行，主 Agent 收集全部结果后综合。

### 4.2 spawn_subagent 支持 expertId（保留 spec §5.3 设计）

`SpawnSubagentTool.spawnSubagent` 入参从 `{prompt, label?}` 扩展为 `{prompt?, expertId?, label?}`：

| 参数组合 | 行为 |
|----------|------|
| `prompt` 有，`expertId` 空 | 现有逻辑：临时子 Agent，`mode-overlay.subagent`，无 skill |
| `expertId` 有，`prompt` 空 | 预定义智能体子 Agent：systemPrompt/skill/tools 来自 expert，query = 主 Agent 当前任务上下文 |
| 两者都有 | 智能体配置 + 主写的 prompt 叠加 |
| 两者都空 | 报错 |

`expertId` 解析：从 `ExpertCatalogService.find(expertId)` 取 `ExpertCatalogEntry`，填入 `systemOverlay` / `skillId` / `toolWhitelist` / `maxIters`。外部智能体（`source=EXTERNAL`）也支持，走 `ExpertExecutorRouter` 分派。

### 4.3 智能体定义模型扩展（保留 spec §3 设计）

`expert_definition` 表扩展：tenant_id / kb_scope_json / data_scope_json / permissions_json / model_config_json / max_iters / source / agent_card_url 等。详见 §3（不变）。

### 4.4 上下文传递：spawn prompt 注入

主 Agent 在 `spawn_subagent(expertId, prompt)` 的 `prompt` 参数中，将关键上下文（前序结果、约束、期望输出）注入子智能体。子智能体在隔离上下文中执行（`AssembledContext.forSubAgent()`），返回终态文本给主 Agent。

**与 HandoffEnvelope 的区别**：不需要结构化交接包。主 Agent 自主决定向子智能体传递什么信息（写入 prompt 参数），子智能体返回终态文本。主 Agent 有全局视角决定如何综合。

**敏感信息隔离**：`AssembledContext.forSubAgent()` 天然隔离 userId/tenantId/会话历史。主 Agent 在写 spawn prompt 时自主决定传递什么（天然脱敏——主 Agent 只传递它认为必要的）。

---

## 5. 架构与改动

### 5.1 整体链路

```
用户问题 -> 意图路由 -> 选中智能体（或通用 ReAct）
  │
  ├─ 有 $ 绑定? -> 主 Agent = 首个 $ 绑定的智能体（ReAct MAIN）
  ├─ 有 @ 绑定? -> ReAct + skill overlay
  ├─ 有 # 绑定? -> 静态 Workflow
  ├─ kind=task? -> ReAct + 工作区沙箱（强制）
  └─ 无绑定 -> 语义路由选中智能体（或通用 ReAct 兜底）
       │
       ▼
  AgentRuntime.run(MAIN)
    │
    ├─ 主 Agent ReAct 循环（think -> tool -> think -> ...）
    │    │
    │    ├─ 需要其他智能体视角时:
    │    │    tool_call: spawn_subagent(expertId, prompt)
    │    │      ├─ source=INTERNAL -> AgentRuntime.run(SUB)
    │    │      │    -> 子智能体执行 -> 终态文本回主 Agent
    │    │      └─ source=EXTERNAL -> ExternalExpertClient.invoke (A2A)
    │    │           -> A2A task 流式 -> 结果回主 Agent
    │    │
    │    ├─ 可并行 spawn 多个（同一轮多个 tool_call）
    │    │
    │    └─ 主 Agent 综合所有子智能体返回 -> 终态正文
    │
    └─ 不需要协作时: 直接工具调用 + 终态正文
```

**关键变化**（vs Agent Team）：
- 路由层不再判断"是否需要团队协作"——路由只选智能体，协作是执行中涌现的
- 无 AGENT_TEAM 执行模式——多智能体协作是 ReAct 内部的 spawn 调用
- 无 TeamOrchestrator/TeamState/HandoffEnvelope——主 Agent 自己管理协作上下文

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
| `SpawnSubagentTool` | 入参加 `expertId` + `resolveAgent`；并行 spawn 支持 | 不变（扩展） |
| `ExpertExecutorRouter` | 按 `source` 分派 INTERNAL/EXTERNAL | （新） |
| `ExternalExpertClient` | A2A Client | （新） |
| `AgentRuntime.run` | 统一执行内核（复用，不改） | 不变 |
| `AgentCatalogService` | 智能体查询（orchestrator 侧，含扩展字段） | `ExpertCatalogService`（重命名） |
| `AgentCatalogClient` | agent-manager HTTP 客户端 | `ExpertCatalogClient`（重命名） |
| `AgentBindingParser` | `$` 绑定解析 | `ExpertBindingParser`（重命名） |
| `AgentBindingRoutingPolicy` | `$` 路由策略 | `ExpertBindingRoutingPolicy`（重命名） |
| `SpawnSubagentTimelineBridge` | spawn 卡片 Timeline（已有，扩展 metadata） | `ExpertTimelineSupport` |
| `SpawnRunRegistry` | spawn 运行注册 + 单独取消（已有） | 不变 |

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
| `ConsultationSynthesizer` | peer-collab 汇总 |
| ExecutionMode `PEER_COLLAB` | **删除**（不替换为 AGENT_TEAM，多智能体协作是 ReAct 内部 spawn） |

---

## 6. 外部智能体市场（A2A 接入）

### 6.1 设计原则：契约统一，执行分派

外部智能体与内部智能体在 `ExpertCatalogEntry` 层统一，对 `SpawnSubagentTool` 透明。执行层按 `source` 字段分派：

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

- `SpawnSubagentTool` 指定 `expertId` 时走 `expertExecutorRouter.invokeExpert`（内部走 AgentRuntime.run，外部走 A2A）

### 6.6 前端 `/agents` 双 tab

| Tab | 内容 |
|-----|------|
| 内部智能体 | 现有 CRUD（systemPrompt / skill / tools / kb / 权限 / 模型编辑） |
| 外部智能体 | 列表 + 新增：填 agentCard URL -> 拉取预填 -> 编辑展示信息 + 鉴权配置 |

外部智能体卡片标记「外部」badge。Chat `$` 补全和 ReAct spawn `expertId` 两个 tab 统一列表，前端补全时展示 source badge 区分。

---

## 7. 智能体间上下文传递原则

### 7.1 问题：现状是全量历史裸传

当前 peer-collab 中，智能体间上下文经 `contextBlocks` **全量累积传递**：

```
contextBlocks = [用户问题, 【智能体A】完整发言, 【智能体B】完整发言, ...]
```

无摘要、无截断、无权限裁剪。spawn 中心化协作中，主 Agent 自主决定向子智能体传递什么信息，天然实现受控传递。

### 7.2 三类不可乱传信息的处理

| 类别 | 风险 | spawn 中心化处理 |
|------|------|-----------------|
| **敏感信息** | userId/tenantId/支付/内部工具返回数据 -> 隐形越权 | `AssembledContext.forSubAgent()` 天然隔离会话上下文（userId/tenantId/会话历史不进子智能体）；主 Agent 在写 spawn prompt 时只传递必要信息（天然脱敏）；工具结果摘要在写入 prompt 前可经 `DesensitizeClient.scrub` |
| **噪声/错误假设** | 模型试错、工具失败、被用户否定的内容带偏新智能体 | 主 Agent 有全局视角，只将确认有效的信息写入 spawn prompt；试错过程保留在主 Agent reasoning，不传递给子智能体 |
| **责任边界** | 交接后谁负责最终回答、谁能调工具 | 主 Agent 始终负责最终正文输出；子智能体 `toolWhitelist` 由其 `toolsJson` 定义（白名单隔离）；子智能体返回终态文本，不参与最终回答 |

### 7.3 上下文传递流程（spawn 中心化）

```
主 Agent ReAct 执行中:
  think: "需要法务视角评估法律风险"
  tool_call: spawn_subagent(
      expertId="legal-agent",
      prompt="评估以下报销条款的法律风险：\n{主 Agent 提取的关键条款内容}\n"
             "背景：{主 Agent 判断必要的上下文}\n"
             "请返回：法律风险点列表 + 严重程度评级")
  -> SpawnSubagentTool 解析 expertId
  -> AgentCatalogService.find("legal-agent") -> ExpertCatalogEntry
  -> 按 source 分派:
       INTERNAL -> AgentRuntime.run(AgentRunRequest.sub(
           forSubAgent(),           // 隔离上下文
           query = prompt 参数,     // 主 Agent 写的任务描述
           systemOverlay = legal-agent.systemPrompt,
           skillId = legal-agent.skillIds[0],
           toolWhitelist = legal-agent.toolsJson,
           maxIters = legal-agent.maxIters))
       EXTERNAL -> ExternalExpertClient.invoke(...)
  -> 子智能体执行完毕，终态文本回主 Agent 作为 tool result
  -> 主 Agent 综合结果 -> 继续 ReAct 循环
```

**与 HandoffEnvelope 的区别**：不需要结构化交接包。主 Agent 自主决定传递什么（写入 prompt 参数），子智能体返回终态文本。上下文传递由主 Agent 的 LLM 判断驱动，而非固定结构。

### 7.4 权限不足处理

子智能体 `toolsJson` 白名单不包含主 Agent 期望的工具时：
- 主 Agent 在写 spawn prompt 时应只请求子智能体能力范围内的任务（主 Agent 可通过 Catalog 查询智能体的工具列表）
- 子智能体执行中遇到无权限的工具调用需求时，在终态文本中说明"无法执行：缺少 X 工具权限"
- **禁止**子智能体猜测或伪造答案

### 7.5 现有安全缺口的修复（与 spawn 中心化一并修）

| 缺口 | 现状 | 修复 |
|------|------|------|
| 智能体层 HITL 被关闭 | `ExpertHubEngine.bindHitlBridge(..., false)` | 按 `permissions.toolConfirmation` 动态决定（always/never/inherit） |
| HITL 确认无身份校验 | `confirmTool` 仅凭 token，不比对发起用户 | `HitlTokenRegistry` 注册时存发起 userId，confirm 时校验 |
| 工具 output 未脱敏 | `ToolAuditService` 只脱敏 params，output 仅截断 240 字符 | output 也走 `DesensitizeClient.scrub` |
| 审计查询无鉴权 | `AuditController` 三接口不校验归属 | 按 conversationId/userId 归属校验 |
| transcript 全文不脱敏 | `peer_run.transcript_json` 含 userQuery 原文 | 落库前对 content 脱敏 |

---

## 8. 执行流程详解

### 8.1 多智能体协作执行流程（spawn 中心化）

```
用户输入 -> 路由 -> AgentRuntime.run(MAIN)
  │
  ▼
主 Agent ReAct 循环:
  think: 分析用户问题，判断需要什么
  │
  ├─ 直接工具调用（RAG/财务/沙箱等）
  │
  ├─ 需要其他智能体时:
  │    tool_call: spawn_subagent(expertId, prompt)
  │      -> SpawnSubagentTool:
  │           expert = AgentCatalogService.find(expertId)
  │           switch expert.source():
  │             INTERNAL -> AgentRuntime.run(AgentRunRequest.sub(
  │                 forSubAgent(),
  │                 query = prompt 参数,
  │                 systemOverlay = expert.systemPrompt,
  │                 skillId = expert.primarySkillId(),
  │                 toolWhitelist = expert.toolsJson,
  │                 maxIters = expert.maxIters))
  │             EXTERNAL -> ExternalExpertClient.invoke(
  │                 expert, query, contextBlocks)
  │      -> 子智能体执行完毕，终态文本回主 Agent
  │
  ├─ 可并行 spawn 多个（同一轮多个 tool_call，互不依赖时）
  │
  └─ 主 Agent 综合所有返回 -> 终态正文输出
```

**与 Agent Team 的区别**：
- 无 `TeamOrchestrator`：主 Agent ReAct 就是编排器
- 无 `TeamState`：主 Agent 的 ReAct 上下文就是全局状态
- 无 `delegate_to_agent`：用 `spawn_subagent(expertId)` 替代
- 无 `finish_task`：子智能体执行完自然返回，主 Agent 决定何时结束
- 无死锁/循环风险：主 Agent 全局控制，子智能体不可再 spawn（与现有 spawn_subagent 约束一致）

### 8.2 并行 spawn

主 Agent 可在同一轮发起多个互不依赖的 `spawn_subagent` 调用：

```
think: "需要同时获取法务意见和财务数据"
tool_call: spawn_subagent(expertId="legal-agent", prompt="评估法律风险...")
tool_call: spawn_subagent(expertId="finance-agent", prompt="查询财务标准...")
-> 平台并行执行两个子智能体
-> 两个终态文本回主 Agent
-> 主 Agent 综合 -> 终态正文
```

### 8.3 `$A $B` 显式多智能体绑定

用户 `$A $B` 时，路由层 L0 `$` 绑定解析：
1. 主 Agent = A（首个 `$` 绑定的智能体）
2. A 的 `systemPrompt` 注入提示："用户要求 B 也参与协作，你可以通过 spawn_subagent(expertId='B', ...) 调用 B"
3. A 在 ReAct 中自主决定何时/是否 spawn B
4. A 综合 B 的返回 + 自己的结果 -> 终态正文

**关键区别**（vs Agent Team `$A $B`）：
- 不预设 roster，不锁定"必须 A 和 B 都发言"
- A 有全局视角，自主决定是否需要 B 的输入
- 如果 A 判断自己能独立解决，可以不 spawn B（避免不必要的协作开销）

### 8.4 智能体子 Agent 的 AgentRunRequest 映射

| 字段 | 来源 |
|------|------|
| `role` | `SUB` |
| `memory` | `AssembledContext.forSubAgent()` |
| `query` | 主 Agent 写的 spawn prompt 参数 |
| `injectedBlocks` | `List.of()`（spawn 不注入额外 block） |
| `skillId` | `agent.primarySkillId()` |
| `toolWhitelist` | `AgentToolsJson.parse(agent.toolsJson())` |
| `systemOverlay` | `agent.systemPrompt()` |
| `maxIters` | `agent.maxIters`（默认 2） |
| `kbScope` | `agent.kbScope` |
| `dataScopeJson` | `agent.dataScopeJson` |
| `permissionsJson` | `agent.permissionsJson` |
| `modelConfigJson` | `agent.modelConfigJson` |
| `timeline` | `SUB_COMPRESSED` |
| `conversationId` | 主会话（沙箱复用） |

---

## 9. Timeline / UI

### 9.1 主时间线步骤形态

```
识别意图     -> …将由「财务智能体」处理…
财务智能体   -> 正在分析… -> 摘要
  └─ subagent-1: 法务智能体 -> 正在评估法律风险… -> 摘要 | 展开详情（含 spawnPrompt + subSteps）
财务智能体   -> 综合法律风险和财务制度… -> 摘要
最终回答     -> （主 Agent 终态正文流式输出）
```

**关键区别**（vs Agent Team）：
- 无 `team-convene` 步——不需要组建团队，主 Agent 直接开始执行
- 子智能体显示为 `subagent-{runId}` 卡片（复用已有 spawn Timeline 组件）
- 无委派箭头——主 Agent -> 子 Agent 是单向 spawn，非对等委派
- 主 Agent 终态正文即综合结论——不需要独立的 TeamSynthesizer 步

### 9.2 Step ID

| id | 说明 |
|----|------|
| `subagent-{runId}` | spawn 的子智能体步骤（已有组件） |
| `metadata.expertId` / `metadata.expertName` | spawn 指定 expertId 时，metadata 增加智能体标识 |
| `metadata.source` | `INTERNAL` / `EXTERNAL`（前端可显示 badge） |

### 9.3 前端改动

- `SpawnSubagentTimelineBridge`（已有）：扩展 `metadata` 增加 `expertId` / `expertName` / `source`
- spawn 卡片 label：指定 expertId 时显示智能体 `displayName`（而非通用「子任务」）
- `/agents` 页（术语重命名后）：智能体定义扩展配置（kbScope / dataScope / permissions / model / maxIters）
- 术语重命名：`/experts` -> `/agents`；文案"专家"->"智能体"
- **不新增** `TeamStepPanel` / 委派箭头 / team-convene 步

### 9.4 Timeline / 取消复用

| 组件 | spawn_subagent(expertId) |
|------|--------------------------|
| 主时间线卡 | `subagent-{runId}` |
| 折叠 Bridge | `SpawnSubagentTimelineBridge`（已有） |
| 取消 | `SpawnRunRegistry` 单独取消（已有） |
| HITL | 复用 SUB `bindHitlBridge`，按 `permissions.toolConfirmation` 动态开关 |

ReAct 中 spawn_subagent 指定 expertId 时，主卡仍为 `subagent-{runId}`，但 `metadata` 增加 `expertId` / `expertName` / `source`，前端卡片可展示智能体名作为 label。

---

## 10. Catalog / 提示词

### 10.1 新增（智能体协作）

| Catalog id | 用途 |
|------------|------|
| `react.spawn-agent.desc` | spawn_subagent 指定 expertId 时的元工具描述（说明可调用预定义智能体） |
| `timeline.steps.subagent` | spawn 子智能体步文案（已有，扩展 label 取智能体 displayName） |

### 10.2 废弃（peer-collab / Agent Team 专属）

- `peer.gather-instruction` / `peer.speak-prompt` / `peer.synthesis-prompt` / `peer.round-continue-prompt` / `peer.round-speakers-prompt` / `expert.coordinator-prompt` / `expert.complexity-prompt`
- `timeline.steps.expert` / `timeline.steps.expert-convene`
- ~~`team.collaboration-overlay`~~ / ~~`team.start-agent-prompt`~~ / ~~`team.synthesis-prompt`~~ / ~~`team.handoff-instruction`~~ / ~~`timeline.steps.team-convene`~~ / ~~`timeline.steps.team-agent`~~ / ~~`react.delegate-to-agent.desc`~~ / ~~`react.finish-task.desc`~~（Agent Team 专属，全部删除）

### 10.3 保留

| Catalog id | 说明 |
|------------|------|
| `mode-overlay.subagent` | 不改；指定 expertId 时不生效（被 expert.systemPrompt 覆盖） |
| `expert.*` 种子 systemPrompt | 需同步调整：写明"须先调用工具检索再给结论"（吸收原 speak-prompt 约束） |
| `timeline.steps.subagent` | 不改；spawn 指定 expertId 时 label 取智能体 displayName |
| `react.subagent.cancel-result` | 不改 |

> 提示词 SSOT 仍在 prompt-manager Catalog，禁止 Java 硬编码。

---

## 11. 调用契约

### 11.1 多智能体协作路径（spawn 中心化）

```
AgentRuntime.run(MAIN)
  └─ 主 Agent ReAct 循环
       └─ tool_call: spawn_subagent({ prompt?, expertId?, label? })
            ├─ expertId 为空: 现有逻辑（mode-overlay.subagent，无 skill）
            └─ expertId 非空: AgentCatalogService.find(expertId)
                 ├─ source=INTERNAL: AgentRuntime.run(AgentRunRequest.sub(
                 │    forSubAgent(), query=prompt,
                 │    systemOverlay=expert.systemPrompt,
                 │    skillId=expert.primarySkillId(),
                 │    toolWhitelist=expert.toolsJson,
                 │    maxIters=expert.maxIters))
                 │    ├─ ReActAgentRuntime
                 │    ├─ 子 think/tool 经 SpawnSubagentTimelineBridge 折叠进 subagent 步 subSteps
                 │    └─ 终态文本回主 Agent 作为 tool result
                 └─ source=EXTERNAL: ExpertExecutorRouter.invokeExpert
                      -> ExternalExpertClient -> A2A task 流式回 tool result
       └─ 主 Agent 综合所有返回 -> 终态正文输出
```

### 11.2 ReAct spawn_subagent 路径（扩展后）

```
主 ReAct (MAIN)
  └─ tool_call: spawn_subagent({ prompt?, expertId?, label? })
       ├─ expertId 为空: 现有逻辑（mode-overlay.subagent，无 skill）
       └─ expertId 非空: AgentCatalogService.find(expertId)
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
| `query` | 主 Agent 写的 spawn prompt 参数 |
| `injectedBlocks` | `List.of()` |
| `skillId` | `agent.primarySkillId()` |
| `toolWhitelist` | `AgentToolsJson.parse(agent.toolsJson())` |
| `systemOverlay` | `agent.systemPrompt()` |
| `maxIters` | `agent.maxIters`（默认 2） |
| `kbScope` | `agent.kbScope` |
| `dataScopeJson` | `agent.dataScopeJson` |
| `permissionsJson` | `agent.permissionsJson` |
| `modelConfigJson` | `agent.modelConfigJson` |
| `timeline` | `SUB_COMPRESSED` |
| `conversationId` | 主会话（沙箱复用） |

---

## 12. 边界与非目标

**做**

- 术语重命名：用户可见层「专家」->「智能体」
- 内部智能体执行内核统一到 `AgentRuntime.run`，消除 `ExpertPeerAgentFactory` / `ExpertSpeakHook` / `ExpertSpeakStreamer` 旁路
- spawn_subagent 支持 `expertId` 调用预定义智能体（内部/外部均可）
- 智能体定义模型扩展：租户/知识库/权限/数据范围/模型/执行限制
- 多智能体协作统一为 spawn_subagent(expertId) 中心化编排（主 Agent 全局视角 + 并行 spawn + 综合结论）
- 完全替换 peer-collab：删除 ExpertHubEngine 全套
- 外部智能体通过 A2A Agent Card 接入，`ExpertCatalogEntry` 统一契约 + 按 `source` 分派执行
- 前端 `/agents` 双 tab（内部/外部）；外部智能体 agentCard URL 注册 + 预填
- 上下文传递：主 Agent spawn prompt 注入（替代 HandoffEnvelope）；隔离上下文 + 敏感信息脱敏
- 修复安全缺口：智能体层 HITL 动态开关 / HITL 身份校验 / 工具 output 脱敏 / 审计查询鉴权 / transcript 脱敏

**不做**

- Sunshine 不实现 A2A Server（只做 A2A Client 消费外部智能体）
- 外部智能体的本地 systemPrompt / skill / tools overlay（远端 Agent 自治）
- 外部智能体 A2A push notifications（仅 streaming 模式）
- 智能体在 Plan/Workflow agent 节点中的直接引用（节点已有 `params.skill` / `params.systemOverlay`，可后续增量）
- 智能体嵌套调用（SUB 仍禁止再 spawn，与 spawn_subagent 一致）
- ~~Agent Team 去中心化协作~~（否决，理由见 §1.3：外部智能体无法参与 + 路由层无法可靠判断 + 组件膨胀 + 与 spawn 高度重复）
- ~~`delegate_to_agent` 元工具~~（用 `spawn_subagent(expertId)` 替代）
- ~~TeamState 共享状态~~（主 Agent 的 ReAct 上下文即全局状态）
- ~~TeamOrchestrator / TeamSynthesizer / RosterManager~~（主 Agent ReAct 即编排器）
- ~~AGENT_TEAM 执行模式~~（多智能体协作是 ReAct 内部 spawn，非独立模式）

---

## 13. 风险与对策

| 风险 | 对策 |
|------|------|
| gather+speak 合并后智能体发言质量下降（不再"先检索再发言"） | expert.systemPrompt + gather-instruction injectedBlock 双重约束；种子智能体文案同步调整；Live 对比前后发言质量 |
| spawn_subagent expertId 与 `$` 路由冲突 | 不冲突：`$` 是路由层 L0 硬绑定（选主 Agent），expertId 是 ReAct 元工具内主 LLM 主动点名（进 SUB），两者正交 |
| 主 Agent 不 spawn 该 spawn 的智能体 | 路由层 `$` 绑定注入提示；主 Agent systemPrompt 写明可调用的智能体列表；Live 验证 |
| 主 Agent 过度 spawn（简单问题也拉人） | mode-overlay.react 约束：仅在确实需要其他领域视角时 spawn；子智能体 maxIters 限制 |
| 子智能体返回质量差 | 智能体 systemPrompt 优化；主 Agent 在 prompt 中明确期望输出格式 |
| 外部智能体网络不可达/超时 | `ExternalExpertClient` 设超时 + 重试 + 降级（返回错误 tool result，主 Agent 可改用内部智能体） |
| 外部智能体鉴权泄露 | `auth_config_json` 只存密钥引用（如 `nacos:expert.auth.legal`），实际 token 从 Nacos 加密配置读取，不落库明文 |
| 外部智能体返回非 text artifact | A2A client 只解析 `text/plain` parts，非 text part 跳过并 warn；后续如需多模态再扩展 |
| 外部智能体与内部智能体 Timeline 不一致 | `ExternalExpertClient` 产出相同 `StreamToken`（step + content），Timeline 形态对前端透明 |
| 智能体 `data_scope` 工具不兼容 | 不强制所有工具支持；不支持的工具忽略（向后兼容）；后续 SDK 逐步适配 |

---

## 14. 检查门

### 14.1 多智能体协作核心（T）

| # | 场景 | 期望 |
|---|------|------|
| T1 | `$policy-agent $finance-agent 分析差旅报销合规性` | 主 Agent = policy-agent；主 Agent 调 spawn_subagent(expertId="finance-agent")；终态正文含两领域综合结论 |
| T2 | ReAct 主 Agent 调 `spawn_subagent(expertId)` | 子智能体执行 -> 终态文本回主 Agent；主卡 `subagent-{runId}` + label=智能体名 |
| T3 | 并行 spawn 多个智能体 | 同一轮多个 spawn_subagent 调用并行执行；主 Agent 收集全部结果后综合 |
| T4 | 子智能体执行失败/超时 | tool result 含错误；主 Agent 可降级处理（自己回答或 spawn 其他智能体） |
| T5 | peer-collab 代码零残留 | grep 无 ExpertHubEngine/ExpertSpeak*/PeerMsgSupport |
| T6 | 敏感信息不进子智能体 | `AssembledContext.forSubAgent()` 隔离；子智能体无 userId/tenantId/会话历史 |
| T7 | 子智能体工具白名单生效 | 仅可调用 `tools_json` 内工具 + `search_knowledge` + sandbox |

### 14.2 智能体定义模型扩展（C）

| # | 场景 | 期望 |
|---|------|------|
| C1 | 智能体 `kb_scope` 生效 | 法务智能体 `kb_scope=["kb-legal"]` 时 `search_knowledge` 仅检索法务库，不查 HR 库 |
| C2 | 智能体 `tenant_id` 隔离 | A 租户用户看不到 B 租户私有智能体；default 智能体全可见 |
| C3 | 智能体 `permissions` 生效 | `toolConfirmation=always` 的智能体调写工具时 HITL 开启；`sandboxWriteMode=never` 禁止沙箱写 |
| C4 | 智能体 `data_scope` 透传 | 工具收到 `ToolAuditContext.dataScope`；业务工具按范围过滤（支持的工具） |
| C5 | 智能体 `model_config` 生效 | 配 `model=gpt-4o` 的智能体实际用 gpt-4o（非全局默认） |

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
| X2 | `$external-legal 分析这份合同风险` | 主 Agent = external-legal -> A2A 执行 -> 终态正文流式 |
| X3 | ReAct `spawn_subagent(expertId="external-legal", prompt="审查条款3")` | 主卡 subagent + label=外部法务智能体；A2A task 流式回 tool result |
| X4 | 外部智能体超时/不可达 | tool result 含错误；主 Agent 可改用内部智能体 |
| X5 | 外部智能体 INPUT_REQUIRED（HITL） | 智能体步 `待确认`；用户确认后续跑（A2A `tasks/send` 续传） |
| X6 | 内部+外部智能体混合 spawn | 两智能体均执行；Timeline 分别标 INTERNAL/EXTERNAL badge |

### 14.5 上下文传递 + 安全缺口（H）

| # | 场景 | 期望 |
|---|------|------|
| H1 | 主 Agent spawn prompt 注入 | 子智能体收到结构化 prompt（含前序结果摘要）；无全量 transcript |
| H2 | 敏感信息不进子智能体 | 子智能体无 userId/tenantId/原始支付数据 |
| H3 | 主 Agent 过滤错误假设 | 试错过程保留在主 Agent reasoning；仅确认信息写入 spawn prompt |
| H4 | 子智能体权限不足 | 终态文本说明"无法执行：缺少 X 工具权限"；不猜测 |
| H5 | 智能体层写工具 HITL | `permissions.toolConfirmation` 控制开关；确认需校验发起用户身份 |
| H6 | 工具 output 脱敏 | 审计记录的 output 经 `DesensitizeClient.scrub` |
| H7 | 审计查询鉴权 | `/api/audit/*` 校验 conversationId/userId 归属 |

Live：`scripts/verify_spawn_subagent_live.py`（扩展 T1-T7 + R1-R5）+ `scripts/verify_external_agent_live.py`（X1-X6，需 mock A2A server）

---

## 15. 后端代码全量重命名（Expert/Peer -> Agent）

原 spec 顾虑 `Expert->Agent` 与现有 `AgentRuntime`/`AgentRunRequest` 命名冲突，经核查为**伪命题**：`AgentRuntime` 在 `com.sunshine.orchestrator.agent` 包，存活类在 `catalog`/`routing` 包，不冲突；expert-manager 的 `com.sunshine.expert` -> `com.sunshine.agent` 是独立服务包路径，不冲突。

### 15.1 重命名映射总表

#### 15.1.1 服务 / 包 / DB

| 旧 | 新 | 说明 |
|----|-----|------|
| `expert-manager` 服务 | `agent-manager` | Spring 服务名 + Nacos 注册名 |
| `sunshine_expert` DB | `sunshine_agent` | MySQL 库名 |
| `expert_definition` 表 | `agent_definition` | 含全部扩展字段 |
| `expert_skill_link` 表 | `agent_skill_link` | |
| `com.sunshine.expert` 包 | `com.sunshine.agent` | expert-manager 全部 Java |
| `com.sunshine.orchestrator.expert` 包 | `com.sunshine.orchestrator.catalog` 包（合并） | orchestrator 侧（存活类移入） |
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
| `ExpertStepLabels` | `SpawnStepLabels` | orchestrator |
| `ExpertTimelineSupport` | `SpawnSubagentTimelineBridge` | orchestrator（复用已有） |
| `ExpertTranscriptEntry` | **删除** | orchestrator（不再用 transcript） |
| `ExpertCollaborationPlanSanitizer` | **删除** | orchestrator（不再需要协作 plan） |
| `ExpertCoordinatorProperties` | **删除** | orchestrator（不再需要 coordinator） |
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
| `PeerCollabPanel.vue` | **删除** | 前端（不再需要） |
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
| `peer.synthesis-prompt` | **删除** | spawn 中心化不需要独立 Synthesizer |
| `peer.round-continue-prompt` | **删除** | 无轮次概念 |
| `peer.round-speakers-prompt` | **删除** | 无选人轮次 |
| `expert.coordinator-prompt` | **删除** | 不再需要 coordinator |
| `expert.complexity-prompt` | **删除** | 不再评估复杂度 |
| `timeline.steps.expert` | `timeline.steps.subagent`（已有） | 复用 spawn Timeline |
| `timeline.steps.expert-convene` | **删除** | 无 team-convene 步 |
| Nacos `peer.synthesis` | **删除** | 非提示词运行参数 |

#### 15.1.6 脚本

| 旧 | 新 |
|----|-----|
| `verify_expert_consultation_live.py` | `verify_spawn_subagent_live.py`（扩展） |
| `verify_peer_collab_live.py` | `verify_spawn_subagent_live.py`（合并） |
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

重命名与 spawn_subagent(expertId) 扩展**合并执行**，不分两步（避免改两遍）：

```
1. DB: 新建 15-sunshine-agent-manager.sql（新表名 + 全部扩展字段）
2. expert-manager: com.sunshine.expert -> com.sunshine.agent（全量重命名包+类）
3. orchestrator: 删除 peer/ 包全量 + expert/ 包中删除类
4. orchestrator: expert/ 存活类重命名 + 移入 catalog/ 包
5. orchestrator: SpawnSubagentTool 扩展 expertId + ExpertExecutorRouter
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

结论：`orchestrator.agent` 包（运行时）与 `agent-manager` 服务 / `orchestrator.catalog` / `orchestrator.routing` 是不同包路径，**全量重命名无冲突**。

---

## 16. 实施衔接

### 16.1 与现有设计的关系

| 现有设计 | 关系 |
|----------|------|
| `2026-07-07-expert-consultation-design.md` | **被本设计取代**（peer-collab -> spawn_subagent 中心化协作） |
| `2026-07-18-react-spawn-subagent-design.md` | 保留并扩展（spawn_subagent 加 expertId 支持） |
| `2026-07-24-expert-as-subagent-design.md` | **被本设计合并**（内部统一 + 外部 A2A 全部并入本文档） |
| `2026-07-28-agent-team-design.md` | **被否决**（Agent Team 去中心化，理由见 §1.3） |
| AS2 P6（peer-collab 正式化） | E5 已否决迁移；本设计替代 P6 的 peer-collab 重构目标 |

### 16.2 任务拆解

| 任务 | 说明 | 阶段 |
|------|------|------|
| `agent_definition` DDL 新建 | 新 SQL `15-sunshine-agent-manager.sql`：`agent_definition` / `agent_skill_link` + 全部扩展字段 | 基础设施 |
| `AgentCatalogEntry` 新建 | DTO 含全部扩展字段（agent-manager + orchestrator 两份） | 基础设施 |
| `AgentRunRequest` 扩展 | 加 `kbScope` / `dataScopeJson` / `permissionsJson` / `modelConfigJson` 透传 | 基础设施 |
| 安全缺口修复 | HITL 身份校验 / output 脱敏 / 审计鉴权 / transcript 脱敏 | 安全（最高优） |
| HITL 动态化 | `bindHitlBridge` 按 `permissions.toolConfirmation` 决定（always/never/inherit） | 权限落地 |
| 沙箱 WriteMode 覆盖 | 按 `permissions.sandboxWriteMode` 覆盖 `SandboxWriteHitlMode` | 权限落地 |
| 模型配置覆盖 | `AgentRuntime` 读 `modelConfigJson` 覆盖 `OpenAIChatModel` | 权限落地 |
| RagTool kbScope | `resolveKbId` 优先读智能体 `kbScope`，覆盖会话级 kbId | 知识库范围 |
| dataScope 透传 | `ToolAuditContext` 加 `dataScope` / `kbScope` / `permissions` 字段 | 数据范围 |
| 后端全量重命名 | expert-manager -> agent-manager；Expert* -> Agent*；Peer* 删除；DB 表名；Nacos；网关（详见 §15） | 重命名 |
| 前端全量重命名 | `/experts`->`/agents`；组件名；文案"专家"->"智能体" | 重命名 |
| `AgentExecutorRouter` | 按 source 分派 INTERNAL/EXTERNAL | 统一分派 |
| `ExternalAgentClient` | A2A Client：`tasks/sendSubscribe` + SSE 事件 -> StreamToken | 外部接入 |
| agent-manager API | 外部智能体 CRUD + Agent Card 拉取预填接口 | 外部接入 |
| `SpawnSubagentTool` 扩展 | 入参加 `expertId` + `resolveAgent`；走 `AgentExecutorRouter`；并行 spawn 支持 | ReAct 集成 |
| `$A $B` 路由改造 | 主 Agent = 首个 $ 绑定；systemPrompt 注入"可 spawn 的智能体列表" | 路由 |
| 删除 peer-collab 全套 | ExpertHubEngine/ExpertSpeak*/PeerMsg*/ExpertRoundCoordinator/ConsultationSynthesizer | 清理 |
| Catalog 废弃 | 废弃 peer.*/expert.* 协作专属 | 提示词 |
| 前端 | `/agents` 配置页扩展 + spawn 卡片 metadata 扩展 + 外部 tab | UI |
| Live | T1-T7 + C1-C5 + R1-R5 + X1-X6 + H1-H7 检查门 | 验收 |

### 16.3 优先级

1. **`agent_definition` DDL 新建 + DTO + AgentRunRequest 扩展 + 后端全量重命名** -- 基础设施 + 重命名合并执行，其他任务依赖
2. **安全缺口修复**（HITL 身份校验/output 脱敏/审计鉴权）-- 影响线上安全
3. **HITL 动态化 + 沙箱 WriteMode 覆盖 + 模型配置** -- `permissions_json` / `model_config_json` 落地
4. **RagTool kbScope + dataScope 透传** -- 知识库范围/数据范围生效
5. **前端全量重命名** -- 低风险，可并行
6. **内部智能体统一执行内核** -- 删 `ExpertPeerAgentFactory` / `ExpertSpeakHook` / `ExpertSpeakStreamer`（重命名时一并删）
7. **spawn_subagent expertId 扩展 + AgentExecutorRouter** -- ReAct 调用预定义智能体（内部/外部统一入口）
8. **peer-collab 删除** -- spawn expertId 稳定后删除
9. **外部 A2A 接入**（ExternalAgentClient + 前端外部 tab）-- 作为 spawn 外部智能体的扩展
10. **`$A $B` 路由改造** -- 多智能体绑定 -> spawn 协作

---

## 17. 自检清单

- [x] 无 TBD/TODO 占位需求
- [x] 统一覆盖：内部统一执行内核 + 外部 A2A 接入 + spawn_subagent 中心化协作 + 智能体定义模型扩展
- [x] 全量重命名：Expert->Agent / Peer 删除（代码 + DB + 服务名 + 前端 + Catalog，命名冲突已排查无冲突）
- [x] 内部/外部在 `ExpertCatalogEntry` 层统一契约，执行层按 `source` 分派
- [x] A2A 仅做 Client（消费外部 Agent Card），不做 Server
- [x] 中心化控制模式：主 Agent 通过 spawn_subagent(expertId) 编排子智能体
- [x] 完全替换 peer-collab：删除 ExpertHubEngine 全套
- [x] 上下文隔离：`AssembledContext.forSubAgent()` 区别于主 Agent 完整上下文
- [x] 上下文传递：主 Agent spawn prompt 注入（替代 HandoffEnvelope）；主 Agent 自主决定传递内容
- [x] 三类不可乱传信息有明确处理（敏感信息隔离 / 噪声过滤 / 责任边界 toolWhitelist）
- [x] 权限不足说明而非猜测
- [x] spawn_subagent 统一为唯一协作机制（expertId 支持内部/外部智能体）
- [x] 无死锁/循环风险：主 Agent 全局控制，子智能体不可再 spawn
- [x] 支持并行 spawn：主 Agent 可同一轮发起多个互不依赖的 spawn_subagent
- [x] 智能体定义模型扩展：tenant_id / kb_scope / data_scope / permissions / model_config / max_iters
- [x] 知识库范围：kb_scope_json 覆盖会话级 kbId，RagTool 按智能体裁剪
- [x] 权限模型：permissions_json 覆盖 HITL/沙箱 WriteMode
- [x] 数据访问范围：data_scope_json 透传到工具层
- [x] 安全缺口（HITL/脱敏/鉴权）一并修复
- [x] 不做兼容兜底（旧两阶段直接删，非 flag 切换）
- [x] 范围可落单一实施计划
- [x] Agent Team 去中心化方案被否决（理由：外部智能体无法参与 + 路由层无法可靠判断 + 组件膨胀 + 与 spawn 高度重复）
