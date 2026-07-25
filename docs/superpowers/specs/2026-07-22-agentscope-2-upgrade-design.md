# AgentScope Java 2.0 分阶段升级与原生能力采纳

> **状态**：已评审（brainstorming 锁定 + 一轮完善性评审 + E5 迁移范围评审）· 实施中（**P0/P1/P2/P3 已完成**）  
> **日期**：2026-07-22（2026-07-23 完善性修订；**2026-07-25 E5 修订：P4/P5/P6 不迁移，保留自研**；**2026-07-25 P3 TaskList 完成**）  
> **关联**：现网 AS **1.0.8** · ReAct 软续跑 · Plan/Workflow `WorkflowCheckpoint`（3.9.5）· TaskBoard（4.7.5）· spawn_subagent（4.7.6）· 沙箱（4.5）· peer-collab  
> **前置决策**：方案 **1（兼容桥先行）** · P0 peer-collab **允许顺序降级 (A)** · AgentState **Redis-only · TTL 7 天 · 不改 MySQL 表** · **统一 HarnessAgent 载体**（§3.1） · **P4/P5/P6 保留自研**（§6 E5）

## 1. 背景

当前平台绑定 AgentScope Java **1.0.8**（`CLAUDE.md` 写明勿升 2.0.0）。ReAct 暂停后续跑为**软续跑**（清空正文、`retainIntentStepsOnly`、新建 Agent 重跑），**无**逐步 checkpoint。Plan/Workflow 已有 MySQL `execution_plan.pause_checkpoint`。

AgentScope Java **2.0** 提供生产向能力：无状态 Agent、`AgentStateStore` + `interrupt` 续跑、`streamEvents`、Middleware、Permission HITL、Harness（Workspace / Subagent / TaskList / Skills）等。目标不是「换 jar」，而是**分阶段用原生能力替换自研 ReAct 内核**，每阶段回归 + 前端真请求通过后再进入下一阶段。

## 2. 目标与非目标

### 目标

| ID | 目标 |
|----|------|
| G1 | 分阶段升至 AS 2.0，编译与主路径可跑，避免一次大爆炸 |
| G2 | 用原生 `stateStore` + `interrupt` 实现 ReAct **真续跑**（非整轮 regenerate） |
| G3 | 逐步用 TaskList / Subagent / Workspace·Permission 替换对应自研实现，**禁止长期双轨** |
| G4 | 每阶段出门闸门：单测 → 相关 Live → **Chat 前端真人路径** |
| G5 | Timeline / SSE / Catalog / 路由 / Plan-Workflow **产品外壳保留** |

### 非目标

- 用 AS **Plan Mode** 替换 Sunshine Plan-Workflow / Workflow Studio / DAG
- 替换：Intent 路由、Prompt Catalog、tool-manager、RAG、审计、租户限流
- 将 `AgentState` 写入或替代 `chat_message.steps`
- 为升 2.0 做强制 MySQL DDL（见 §4）
- 一次替换全部自研（TaskBoard / spawn / 沙箱 / peer 必须分阶段）

## 3. 总体策略

**方案：兼容桥先行（方案 1）**

```text
AS 2.0（推理 · AgentState · Tool/HITL 事件 · Subagent · Workspace）
        ↓ EventAdapter / 明确标注的临时桥
Sunshine（Timeline · SSE · GenerationJob · Catalog · 路由 · Plan/Workflow · RAG · 审计）
```

| 原则 | 说明 |
|------|------|
| 外壳自留 | Timeline V2 契约、BFF/Gateway、前端 UX |
| 内核可替 | Agent 状态、续跑、TaskList、Subagent、沙箱执行内核、ReAct Permission HITL |
| 禁双轨 | 同能力过渡期可有桥，阶段结束必须拆桥 |
| 闸门失败不跨阶段 | 禁止带着红灯开下一阶段 |

**P0 peer-collab**：`MsgHub` 在 2.0 已删除 → 允许**顺序发言降级**（功能可用、弱于现网反应式 hub），由自研 `ExpertHubEngine` 驱动反应式选人（G-a 路径 1，E5 确认为终态，不再设 P6 迁移阶段）。

### 3.1 Agent 载体选型（E3 修订，P0 前必须定调）

AS 2.0 为**双层架构**：

| 层 | 能力 | 覆盖本方案阶段 |
|----|------|----------------|
| `ReActAgent` | 无状态推理 · `stateStore`+`interrupt` · `streamEvents` | P2 已够用 |
| `HarnessAgent`（上层） | 在 ReAct 之上加 Workspace / **Compaction** / **TaskList** / **Subagent** / Channel | **Compaction**（P2）与 **TaskList**（P3）必需；Subagent / Workspace 经 E5 评审不采用 |

**决策：ReAct 主路径统一到 `HarnessAgent` 单例载体（P0 即定型，不在 P2→P3 之间二次迁移载体）。**

理由：HarnessAgent 的 **CompactionConfig 仅在上层可用**（§5 注），这正是统一载体的直接动因；TaskList（P3）亦为上层能力。一次到位到 HarnessAgent，P2 用其 stateStore/interrupt/streamEvents + Compaction，P3 增量启用 TaskList，载体不变。（E5：Subagent / Workspace 能力经评审不启用，不影响本决策——统一载体的动因是 Compaction，非 Subagent。）

**兼容性核对**：`HarnessAgent.streamEvents()` 与 `ReActAgent.streamEvents()` 同签名（官方 changelog B.4），P1 事件契约不受影响；`.enableTaskList(true)` / `.subagent(...)` / `.workspace(...)` / `.compaction(...)` 为 builder 增量项，P2 不启用即可。

## 4. AgentState 持久化约定（已锁定）

### 4.1 决策

| 项 | 约定 |
|----|------|
| 存储 | **仅 Redis**（`RedisAgentStateStore` / DistributedStore 等价配置） |
| TTL | **7 天**（604800s）；过期后不可检查点续跑 |
| MySQL | **不改表**；不把 AgentState 写入 `chat_message` / `steps` |
| sessionId | **`assistantMessageId`**（消息级续跑；禁止用 `conversationId` 以免多轮串态）——语义与冲突见 §4.1a |
| 与 Timeline | **正交**：MySQL `steps` = UI；Redis AgentState = 推理上下文 |

### 4.1a sessionId 语义澄清（E4 修订，写实施计划前钉死）

**续跑恢复的是「该条 assistant 消息的推理现场」，不是「整段对话的连续上下文」。**

- 每条 assistant 消息 = 一个独立 `(userId, assistantMessageId)` 槽位；同一会话的多条消息**不共享** AgentState。
- 官方并发模型：**同一 `(userId, sessionId)` 的 call 自动串行，不同则并行**。消息级 sessionId 意味着：
  - 同一对话的两条消息并行（不同 sessionId），天然无串态风险 ✅（正是禁止 `conversationId` 想要的效果）
  - 代价：放弃「同 session 串行」的并发保护——但 Sunshine 本就用 `GenerationJob` + stream epoch 做并发与脏写防护，不依赖 AS 的 session 串行门 ✅
- 对话历史如何进入推理：由 Sunshine Prompt 拼装层（`PromptComposer` + `AssembledContext`）负责注入历史，**不依赖** AgentState 跨消息延续。AgentState 只承载「本条消息中断时的 ReAct 中间现场」（已走到的 reasoning/acting 步）。
- **结论**：消息级 sessionId 与 G2「真续跑」目标一致（续跑 = 从该消息中断点继续，而非 regenerate 整轮）；与官方 session 语义无冲突，因 Sunshine 显式绕开了 AS 的 session 串行并发模型。

### 4.2 为何 Redis-only 够用

- 与现网 `GenerationJob` Redis 缓冲同属「运行时会话态」
- 多副本续跑需要共享 Store
- 官方生产推荐 Redis；MySQL Store 偏审计/报表，非续跑刚需
- 聊天历史与步骤展示仍靠 MySQL，不依赖 AgentState 长期存活

### 4.3 降级与运维

| 场景 | 行为 |
|------|------|
| TTL 过期或 Redis 丢失 | 按钮退回「重新生成」；明确提示；**禁止**静默空跑 |
| Redis flush / 不当 eviction | 同上；运维：key 前缀隔离，避免 `allkeys-lru` 误杀；生产建议 AOF |
| 未来要审计 / 超 7 天必续 | **另立项**（独立 Store 或审计表）；不在本升级强制范围内 |

### 4.4 现网 MySQL 仍持久化（不变）

`chat_message`：`content`（用户问题 / 助手回答）、`reasoning`、`steps`、`content_blocks`、`status`、路由元数据等。  
另有：`execution_plan.pause_checkpoint`（Plan/Workflow）、`react_task_board`、`peer_run`、上下文 L1/L2 等。  

说明：SQL 中存在未使用的 `react_pause_checkpoint` 列——**本方案不启用、不与 AS State 混用**；清理属可选后续杂项。

## 5. 可被原生替代的自研能力

| 自研 | AS 2.0 | 阶段 |
|------|--------|------|
| ReAct 软续跑 | `stateStore` + `interrupt` | P2 |
| `AutoContextHook`（hook 包，2.0 **整体移除**） | **HarnessAgent `CompactionConfig`**（`triggerMessages`/`keepMessages`，可独立小模型 `.model()`/`.prompt()`） | P2 起对齐，禁双轨 |
| TaskBoard `manage_tasks` | `enableTaskList` + TodoTools | P3 |
| 每请求 `new ReActAgent` | 无状态 HarnessAgent 单例 + `RuntimeContext` | P2 |
| ~~`spawn_subagent`~~ | ~~Harness Subagent + distributedStore~~ | ~~P4~~ **评审后不迁移**（§6 P4，E5 修订） |
| ~~沙箱执行内核（部分）~~ | ~~Workspace / DockerFilesystemSpec~~ | ~~P5~~ **评审后不迁移**（§6 P5，E5 修订） |
| ~~ReAct 写工具 HITL~~ | ~~Permission + `RequireUserConfirmEvent`~~ | ~~P5~~ **评审后不迁移**（§6 P5，E5 修订） |

> **E5 修订（2026-07-25）**：P4 / P5 / P6 经官方文档逐项对照评审后决定**不迁移，保留自研**。核心理由：官方原生能力集中在执行内核，但 Sunshine 的产品承诺（单独取消不 bump epoch、对话级沙箱容器共享、editDiff 抽屉、六工具 schema、`writeHitlMode` 三态、sha256 审计、反应式选人）全在外壳——外壳占比超 80%，迁移后代码量不减反增（多一层桥接），且部分语义（对话级隔离粒度、仅首次确认）官方无等价物，强迁会破坏产品承诺。P2 的单例优化 + 原生 stateStore 自动保存才是 2.0 对本平台的净收益点。详见 §6 P4/P5/P6 各节决策记录。

> 注：Sunshine 现网用的是 `AutoContextHook`（`io.agentscope.core.memory.autocontext`），**不是** `AutoContextMemory`；2.0 原生替代是 `CompactionConfig`，且**仅在 HarnessAgent 上可用**——这正是 §3.1 统一 HarnessAgent 载体的直接动因。
>
> **P0-3 实施校准（2026-07-23 实测）**：`memory/autocontext` 包在 2.0 **整体删除**（AutoContextHook/AutoContextMemory/AutoContextConfig 均不编译，**非** LegacyHookDispatcher 可桥），P0 已移除全部引用、压缩能力暂退至 SDK 默认（无压缩），P2 以 `CompactionConfig` 恢复、阈值对标 `MemoryProperties.AutoContext`（字段保留）。另：`RedisAgentStateStore` 在 `io.agentscope.extensions.redis.state`，**builder-only + lettuceClient、无 TTL 参数**，P2 须另行落地 TTL（clientAdapter / keyPrefix 包装 / SDK 升级三选一）。

**不可替代（继续自研）**：Plan-Workflow / Studio、静态 Workflow 检查点、路由与 Prompt Catalog、peer 产品层（Catalog/`$`/Synthesizer/反应式 hub）、RAG、tool-manager、审计、Timeline/SSE 外壳、**spawn_subagent 全栈**（E5）、**沙箱六工具 + 取消 + editDiff + 审计全栈**（E5）、**HITL 判定与续跑**（E5）。

## 6. 分阶段技术方案

每阶段出门闸门统一为：

1. 相关单测通过  
2. 指定 `verify_*` / demo Live  
3. Chat **前端真请求**（该阶段涉及的执行模式）人工确认  
4. **回滚测试全绿**（见 §7.5a，强制项）

闸门未过 → 禁止开下一阶段。

### P0 — 依赖可编译可跑

**2.0 完整删除/迁移清单（E1 修订，P0 必须全部处理，不止 MsgHub）**

| 类别 | 1.x → 2.0 | Sunshine 命中点 |
|------|-----------|----------------|
| 模型实现迁出 core | `io.agentscope.core.model.OpenAIChatModel` → `io.agentscope.extensions.model.openai.OpenAIChatModel` | `ReActAgentFactory.java:96`、`ExpertPeerAgentFactory.java:40` |
| **Builder 方法删除** | `.memory(Memory)` / `.statePersistence()` 已删 → `.stateStore(AgentStateStore)` | `ReActAgentFactory` builder 链 |
| **pipeline 整包删除** | `io.agentscope.core.pipeline.*`（含 `MsgHub`）→ middleware / subagent / 事件流 | `ExpertHubEngine.java:83` |
| `SessionManager` 删除 | → `.stateStore()` | 现网未直接用，确认即可 |
| `stream()` 粗粒度弃用 | `Flux<Event> stream()` → `streamEvents()`（`Flux<AgentEvent>`，28 类） | P1 主线，P0 仅占位 |
| `io.agentscope.core.plan.*` 删除 | 整包删（PlanNotebook 等） | 现网未用（Plan-Workflow 自研），确认即可 |
| hook 包软弃用 | `io.agentscope.core.hook.*`（含 `AutoContextHook`）→ 由 `LegacyHookDispatcher` 桥接，**可编译** | `ProcessingStepHook`、AutoContext 注入点——P0 可暂留，P7 拆 |

**范围**

- `agentscope.version` → 2.0.x；引入 `agentscope-extensions-model-openai`
- `OpenAIChatModel` 包迁移（extensions）；Gateway `/v1` 对接保持
- **P0 即定型 HarnessAgent 载体**（§3.1）：Factory 产出 HarnessAgent 单例骨架，builder 仅配 model + 占位 `stateStore`；`.memory(...)` 调用全部改 `.stateStore(...)`
- `ExpertHubEngine`：去掉 `MsgHub`，改为**顺序**调用专家；广播上下文改为显式 `contextBlocks`；标记 `AS2_P0_PEER_SEQUENTIAL`
- hook/AutoContext 暂留 `LegacyHookDispatcher` 桥，**本阶段不宣称续跑**、不启用 compaction

**不改**：Timeline 契约、Catalog、Plan checkpoint、沙箱产品层、MySQL DDL

**闸门**：编译；基础 ReAct Chat 前端；peer-collab `$` 能出专家步（不要求与现网轮次完全一致）；上表 7 类删除项逐一确认无残留编译错误

### P1 — 事件契约 `streamEvents` → Timeline

**范围**

- `ReActAgentRuntime`：迁到 `streamEvents`（或等价）
- `AgentScopeEventMapper`：`AgentEvent` → `StreamToken` / Timeline；正文仍经 `ContentSegmentCoordinator`；**禁止**截断/摘要模型输出
- Hook 可暂留 `LegacyHookDispatcher`，目标减少对旧 `Event` 硬依赖

**数据流**：`streamEvents` → EventAdapter → `StepEventBridge` / Timeline → `GenerationJob` → SSE

**闸门**：ReAct / Workflow agent 节点步骤与流式正文前端一致；**适配层性能基线**（G-e）：单请求事件适配吞吐 / P99 延迟不劣于 1.0.8 hook 路径 10%（sse-pipe 基准脚本，P1 前补 `scripts/bench_event_adapter.py`）

### P2 — 原生 ReAct checkpoint / resume

**范围**

- HarnessAgent 单例 + `RuntimeContext(userId, sessionId=assistantMessageId)`（语义见 §4.1a）
- Redis `AgentStateStore`，**TTL=7 天**，key 前缀与 GenerationJob 分离，租户可感知前缀
- 停止：`GenerationJob.cancel` → `interrupt(ctx)`，依赖框架落盘
- 续跑：有可用 State →「继续执行」；**废除** ReAct 路径的 `retainIntentStepsOnly` 清空重跑
- 前端：`resolveResumeMode` 在有 checkpoint 时对 ReAct 返回 `checkpoint`；保留已有 steps，新事件 append
- **上下文压缩对齐**：现网 `AutoContextHook` 在 HarnessAgent 上替换为 `CompactionConfig`（`triggerMessages`/`keepMessages` 初值对标现网 AutoContext 阈值，可调）；保留压缩时机与 Catalog 提示词行为不变
- MySQL **零 DDL**

**中断在 tool 执行中**：以 AS 语义为准，Live 用例钉死「重做 vs 跳过」预期。

**闸门**：新 Live（如 `verify_react_checkpoint_live.py`）；前端停→续步骤连续、已完成 tool 不无故整轮重来

### P3 — TaskList 替换 TaskBoard ✅（2026-07-25 完成）

**范围**：`.enableTaskList(true)` + TodoTools + TaskReminderMiddleware；下线 `manage_tasks` 主路径；Timeline 仍投影为单一 `tasks` 步（前端尽量零改）

**实现要点（已落地）**

| 项 | 实现 |
|----|------|
| 启用点 | `ReActAgentFactory`：仅 `role == MAIN` 且 `agent.execution.react.taskboard.enabled=true` 时 `builder.enableTaskList(true)`；SUB / 专家（`ExpertPeerAgentFactory` 独立 builder）/ workflow agent 均不开任务板 |
| 状态持久化 | 原生 `todo_write` 写 `AgentState.tasksContext`，随 checkpoint 落 `AgentStateStore`，**中断恢复后任务列表（含 id）随 stateStore 还原**——根治自研 `manage_tasks`（Redis 独立存储）恢复丢 id / merge 校验失败 |
| Timeline 投影 | 新增 `TodoTasksBridge`：原生 `Task` 列表 → timeline `TaskBoardItemView`，经 `ProcessingStepMiddleware.completeToolStep` 在 `todo_write` 完成时投影到单一 `tasks` 步（前端零改，`TaskBoardPanel` 通用渲染 `metadata.tasks`） |
| 去单独 think 步 | `todo_write` 是状态工具（无结果可分析），**不 `recordToolCompleted`** → 不再触发「已完成任务板的工具结果综合分析」think 步；`todo_write` 后推理复用同一 think（连续 reasoning 合并） |
| 终态收口 | `ReActAgentRuntime.finishAnswerStream` 传 `HarnessAgent` → `ReactTaskBoardService.finalizeNativeTimeline` 从 `tasksContext` 读任务，完成 timeline `tasks` 步 + **终态落 MySQL 审计**（`persistFinal`，审计数据源从 Redis 改为 tasksContext） |
| 自研遗留清理 | 删除 `ManageTasksTool` / `ReactTaskBoardStore`(Redis) / `TaskBoardContentMatch` / `TaskBoardItemInput` / `ReactTaskBoardApplyResult`；`ReactTaskBoardService` 删 `apply/load/emitTimelineUpdate/finalizeTimeline/saveState` 及全部 merge 校验；审计删 `onUpdated/shouldSample`；清理 `ProcessingStepMiddleware`/`ExpertSpeakHook`/`DynamicToolkitFactory` 的 `manage_tasks` 分支；删死配置 `taskboard.{maxItems,maxInProgress,seedFromInjectedSummary,audit.sampleRate}` + `as2.tasklistNative` |
| 提示词 | `mode-overlay.react` v2 已发布（`manage_tasks` → `todo_write` 全量替换语义：每次传完整 todos 列表，平台按 content 自动保留原 id，模型不管 id）；SQL 种子 `17-sunshine-prompt-manager.sql` 同步 |

**决策修订（相对原 P3 设计）**

- **一次性迁移，无 feature flag**：原 §7.5 规划的 `agent.tasklist.native` 回滚 flag 未落地——用户决策全量切换、自研代码直接删除（非保留双轨）。回滚靠 `git revert`（自研 `manage_tasks` 全链路单 commit 可恢复），不再需要 `verify_rollback_p3_tasklist.py`。
- **保留 MySQL 审计**：终态 `tasksContext` 快照仍落 `react_taskboard` 表（`ReactTaskBoardAuditService.persistFinal`），仅删过程事件 `onUpdated`。

**闸门（已通过）**

- 单测：`ReactTaskBoardTest`（含 3 个 `finalizeNative*` 新增）/ `TodoTasksBridgeTest`（新增）/ `DynamicToolkitFactoryTest` / `AgentInfraTest` 等 25/25 绿
- Live：`scripts/verify_tasklist_native_live.py`（新建，强制 `executionPreference=react`）—— N1 `tasks` 步含 4 items / N2 全程无 `manage_tasks` / N3 无「任务板的工具结果综合分析」think 步，全过；前端任务卡正常渲染

### P4 — ~~Harness Subagent 替换 spawn~~ → 保留自研（E5 修订，2026-07-25）

**决策：不迁移，保留 `SpawnSubagentTool` 全栈自研。**

**评审依据（官方 Harness Subagent 逐项对照）**

| 维度 | Sunshine 现状 | 官方原生 | 结论 |
|------|--------------|----------|------|
| 委派触发 / 隔离 / 嵌套保护 / 并行 / 超时 | 元工具 + `AssembledContext.forSubAgent()` + 硬拒 | `agent_spawn` + ISOLATED + 叶子保护 | ✅ 等价，可迁 |
| 流式转发 | bridge 折叠 `subagent-{runId}.subSteps` | `streamEvents` `source` 字段 | ✅ 等价 |
| **单独取消** | `SpawnRunRegistry.cancel` → `interrupt()` + 父卡 paused SSE 直写 GenerationJob + **不 bump epoch** | `task_cancel` 仅覆盖后台任务（`timeout_seconds=0`），后台任务**不支持流式转发**；同步模式的取消语义与 GenerationJob/stream epoch 模型无等价物 | ❌ 保留 `SpawnRunRegistry`（G-b 已锁定） |
| **沙箱会话共享** | `conversationId` = 主会话 → 同一对话级容器 | ISOLATED 独立工作区 / SHARED 共享 Harness workspace（≠ Sunshine 对话级容器） | ❌ 保留 |
| **HITL 抽屉** | `bindHitlBridge(subBridgeId, messageId)` | Permission 继承父 DENY（解决权限边界，不解决抽屉确认 UX） | ❌ 保留 |
| **工具集** | 与 MAIN 同 Catalog（`toolSetResolver` 运行时按租户动态解析） | 构建期声明白名单或继承父 | ⚠️ 可桥但桥接层不薄 |
| 主卡 / 抽屉 UI / Catalog 提示词 | `subagent-*` 卡 + `PlanNodeDrawer` + `mode-overlay.subagent` | `SubagentExposedEvent` / Channel | ❌ 产品外壳保留 |

**净收益评估**：外壳（取消/HITL/沙箱/UI/Catalog）占复杂度 80%+，迁移后变为「官方内核 + 自研外壳 + 桥接层」，代码量不减反增，且工具集语义（同集 vs 继承子集）需额外适配。`SpawnSubagentTool` 已过 S1/S4/S5 验收，无痛点驱动重构。

**保留项全清单**：`SpawnSubagentTool`、`SpawnRunRegistry`（G-b）、`SpawnSubagentTimelineBridge` / `SpawnSubagentTimelineSupport`、`bindHitlBridge`、沙箱会话共享注入、`mode-overlay.subagent` / `react.subagent.cancel-result` Catalog、前端 `SubagentCard` + 抽屉。

**原 G-b 钉死条款效力**：继续有效——`SpawnRunRegistry` 不是「适配层」，而是**永久保留的产品实现**，不再是「原生不支持时的兜底」。

> **附：自研 SSE epoch 闸门是永久维护项（2026-07-25 案例）**。恢复续跑 + 新 spawn 子任务曾卡死（子任务 token 积压在 hookQueue 等主 Agent drain，前端直到子任务完成才显示）。根因：`StepEventBridgeRegistry.bind()` 用 bridgeId（`sub-{runId}`）当 `streamEpoch` 键取 epoch，而该 map 键是 assistantMessageId → 取到 0，与 `bumpStreamEpoch` 抬升后的 bindingEpoch 错配，`isHookFlushAllowed` 拒直刷。修复：`bind`/`bindHitlBridge` 经 `hitlAssistantMessageId` 解析回 assistantMessageId 再取/校准 epoch + `bumpStreamEpoch` 改 `compute` 保证首次单调递增（回归 `StepEventBridgeRegistryResumeEpochTest`）。**结论**：这套 `streamEpoch`/`GenerationJob`/hookQueue 直刷闸门是 Sunshine 自研的前端实时投影机制，与 AS 2.0 无关——即使 P4 迁到 Harness 原生 subagent，只要仍用此桥接做「主时间线子卡 + 抽屉 subSteps」投影，epoch 对齐就需自己维护；唯有把恢复+子任务实时投影也整体交给原生统一事件流，才能废弃。

### P5 — ~~Workspace 沙箱 + Permission HITL~~ → 保留自研（E5 修订，2026-07-25）

**决策：不迁移，保留沙箱六工具 + 取消 + HITL 全栈自研。**

**评审依据（官方 Workspace / Permission 逐项对照）**

| 维度 | Sunshine 现状 | 官方原生 | 结论 |
|------|--------------|----------|------|
| 沙箱快照恢复 | `SandboxSessionLifecycle` 容器随对话生灭 | `DockerFilesystemSpec` sessionId 快照（含 `node_modules`） | ✅ 官方更优，但见下条 |
| **隔离粒度** | **对话级容器**：`conversationId` 一个容器，MAIN + 所有 SUB + 同对话多条消息共享 | `IsolationScope.SESSION`（每 sessionId 隔离）；Sunshine sessionId = `assistantMessageId`（§4.1a）→ 每条消息一个沙箱，**前一条消息装的环境下一条即丢失** | ❌ **产品承诺破坏**，保留 |
| **六工具 schema** | `sandbox__{exec,grep,glob,read,write,edit}`，edit 带 `old_string`/`new_string` 精确替换 | `execute`/`read_file`/`write_file`/`grep_files`/`glob_files`，无精确 edit | ❌ 保留 schema + 命名（Catalog/Timeline/前端零改的前提） |
| **editDiff 抽屉** | `SandboxEditDiffHolder` + unified diff 展示 | 无等价物 | ❌ 保留 |
| **单工具取消** | `CancellableToolRunRegistry` + sandbox kill + 同族预算 3 + `paused` SSE 文案 | 无等价取消粒度 | ❌ 保留（G-c 已锁定） |
| **sha256 审计** | `ToolAuditService` 敏感字段脱敏 | 无等价物 | ❌ 保留 |
| **HITL 判定** | `SandboxHitlPolicy` + `writeHitlMode` 三态（全确认/仅首次/不确认） | `PermissionRule` 按 `toolName + ruleContent` 模式匹配，无「仅首次确认」会话级语义 | ❌ 保留判定逻辑 |
| **HITL 续跑** | `consumeHitlPreApproval` 跳过二次确认 | `ConfirmResult` 附 metadata 新 call 恢复；checkpoint 后 `AgentState.getPermissionContext()` 已确认规则完整性未验证 | ❌ 保留 |
| HITL 传输层 | `HitlConfirmationService.awaitConfirmation` | `RequireUserConfirmEvent` + `ConfirmResult` | ⚠️ 唯一可迁项，但见下 |

**HITL 传输层为何不单独迁**：传输层（事件收发）与判定层（`SandboxHitlPolicy` + `writeHitlMode` + `consumeHitlPreApproval`）在 `SandboxAgentTools.execute` 内深度交织（`awaitConfirmation` 内联在工具执行流里，前后各有取消检查 / stale epoch 检查 / 预算扣减）。只换传输层需要把执行流拆成「官方事件驱动」模型，重构成本接近全迁，收益仅是少一个 `awaitConfirmation` 自研实现——不划算。

**净收益评估**：官方唯一明确更优的是「沙箱快照含 `node_modules` 恢复」，但代价是隔离粒度从对话级降为消息级——这是方案 B 的核心设计，不可接受。其余各项官方能力均已被 Sunshine 自研覆盖或超越。

**保留项全清单**：`SandboxAgentTools`（六工具）、`CancellableToolRunRegistry`（G-c）、`SandboxSessionLifecycle` / `SandboxSessionHolder`（对话级容器）、`SandboxEditDiffHolder` / `SandboxEditDiffCodec`、`SandboxHitlPolicy` / `SandboxWriteHitlMode`、`HitlConfirmationService`、`consumeHitlPreApproval`、`ToolAuditService` sha256、前端 hover 取消钮 + 抽屉 diff + `HitlStepActions`。

**原 G-c 钉死条款效力**：同 G-b——`CancellableToolRunRegistry` 转为**永久保留的产品实现**。

### P6 — peer-collab 正式化（G-a 路径 1 已锁定，E5 补充确认）

**决策：不迁移（spec 原已锁定路径 1），P0 顺序桥即为终态调用方式。**

P0 已完成「去掉 MsgHub → 顺序调用专家 HarnessAgent `streamEvents`」，这就是路径 1 的落地形态。「第 2 轮起反应式选人」由自研 `ExpertHubEngine` 决策函数驱动，与 AS 原生多智能体无关——P6 不存在「迁移」动作，仅需在 P7 确认无 Legacy 残留。

**P6 阶段取消**，相关验收（`verify_peer_collab_live` / `verify_expert_consultation_live`）并入 P7 回归包。

### P7 — 清桥收口

**范围**：删除 Legacy Hook/Memory/顺序桥/双实现开关；更新 `CLAUDE.md`（取消「勿升 2.0」；写明 ReAct 续跑依赖 Redis StateStore TTL=7d）；全量相关 Live + 四模式前端抽检

## 7. 风险与错误处理

| 风险 | 缓解 |
|------|------|
| MsgHub 删除导致 P0 编译不过 | 顺序桥为硬前置 |
| P1 事件映射双写正文 | 单一适配器；沿用 ContentSegment 规则 |
| State 与 steps 续跑策略冲突 | P2 禁止清空 steps；State 缺失则 regenerate |
| 双轨 TaskBoard | 阶段结束拆桥；禁止两套执行器并行 |
| 误用 Plan Mode | 文档与评审明确禁止 |
| HarnessAgent 未定型导致 P2→P3 二次迁移 | §3.1 前置定型，P0 即落 HarnessAgent 骨架 |
| 反应式 hub 在 2.0 下不可行 | P0 spike 已验证路径 1（G-a）可行；E5 确认为终态，无 P6 迁移风险 |
| 消息级 sessionId 并发失控 | §4.1a：靠 GenerationJob + stream epoch 防护，不依赖 AS 串行门 |

## 7.5 回滚策略（G-d，按阶段）

| 阶段 | 回退单元 | 回退动作 |
|------|----------|----------|
| P0 | jar 版本 | 回 `agentscope.version` 至 1.0.8 + revert P0 commit |
| P1 | jar + 事件适配器开关 | `agent.events.legacy-hook=true` 切回 hook 路径（P1 期间保留双路径开关，P7 才拆） |
| P2 | feature flag | `agent.react.checkpoint.enabled=false` → 回 `retainIntentStepsOnly` 软续跑（代码保留至 P7） |
| P3 | git revert（一次性迁移，无 flag） | 自研 `manage_tasks` 全链路单 commit 恢复；orchestrator 重启 + `mode-overlay.react` 回滚 v1 |

> **E5 修订**：P4 / P5 / P6 不迁移（§6 各节决策记录），无双轨，无需 feature flag 与回滚脚本。

**原则**：每阶段合入必须带同名 feature flag（默认开），flag 全保留到 P7 清桥统一删除；线上出事故**先切 flag 再查因**，禁止直接回版本导致跨阶段混杂。

### 7.5a 回滚测试规范（强制项，每阶段闸门 #4）

回滚不是"出事再切"，而是**每阶段必须主动演练并自动化验证**。每阶段合并前，按下列用例跑通并留记录（脚本 `scripts/verify_rollback_<phase>.py`，纳入 §8 自动化列）：

**通用三段式（每阶段必做）**

1. **正向**：flag=开（新路径）→ 跑该阶段核心 Live → 确认新行为生效
2. **回滚**：切 flag=关 → 重启 → 跑**同一套 Live** → 断言回退到上一阶段行为
3. **回切**：切 flag=开 → 重启 → 跑**同一套 Live** → 断言新行为恢复，且**无脏数据残留**（Redis State / MySQL steps / GenerationJob 缓冲三处一致）

**分阶段专项断言**

| 阶段 | 回滚脚本 | 关键断言 |
|------|----------|----------|
| P0 | `verify_rollback_p0_compile.py` | 切 1.0.8 jar 后 `mvn -pl orchestrator -am compile` 绿；peer 顺序桥 flag 切换无编译残留 |
| P1 | `verify_rollback_p1_events.py` | legacy-hook ↔ streamEvents 双路径切换，Timeline 步骤数 / SSE 正文 **逐字节一致** |
| P2 | `verify_rollback_p2_checkpoint.py` | checkpoint ↔ 软续跑切换：同一会话先停→续（checkpoint），回滚后停→**重新生成**（软续跑），steps 不丢失、Redis State 不串会话 |
| P3 | ~~`verify_rollback_p3_tasklist.py`~~（一次性迁移，无 flag，不需要） | 回滚靠 git revert + 提示词回滚 v1；任务卡 UI 数据由 `verify_tasklist_native_live` 守护 |

> **E5 修订**：P4 / P5 / P6 不迁移，对应回滚脚本（`verify_rollback_p4_subagent` / `p5_sandbox` / `p6_peer`）不再需要；已有的 spawn/沙箱/peer Live 脚本（`verify_spawn_subagent_live` / `verify_sandbox_tool_cancel_live` / `verify_peer_collab_live` 等）作为**常驻回归**并入 P7 回归包，继续守护自研实现。

**脏数据清零检查（每次回滚后必跑）**：Redis `FLUSHDB` 不可取——按 key 前缀清：`agentscope:state:*` / `agentscope:tasklist:*` / `agentscope:subagent:*`；MySQL 检查 `chat_message.steps` 无半写入；GenerationJob Redis stream 无孤立 generationId。

**门槛**：回滚脚本与正向 Live 同等级，**红一个即整阶段红灯**，禁止带病进入下一阶段。

## 8. 验收总表（按阶段）

| 阶段 | 自动化 | 前端真请求 |
|------|--------|------------|
| P0 | 编译 + 核心单测 + 基础 demo + **7 类删除项零残留** + 反应式 hub spike 结论 + `verify_rollback_p0_compile` | ReAct 一轮对话；peer 降级可用 |
| P1 | Timeline/事件相关单测 + **适配层性能基线达标** + `verify_rollback_p1_events` | 步骤 + 流式正文 |
| P2 | `verify_react_checkpoint_live`（新建） + `verify_rollback_p2_checkpoint` | 停→「继续执行」 |
| P3 | `verify_tasklist_native_live`（N1 tasks 步 items / N2 无 manage_tasks / N3 无任务板单独 think 步）+ 任务板单测 25 绿 | 任务卡 |
| P7 | 回归包 + feature flag 全拆 + **全量回滚脚本最终回归** + spawn/沙箱/peer 常驻 Live 回归（`verify_spawn_subagent_live` / `verify_sandbox_tool_cancel_live` / `verify_peer_collab_live` / `verify_expert_consultation_live`） | react/workflow/plan/peer 抽检 + 子卡/取消 + 沙箱抽屉/写确认 + `$` 完整路径 |

> **E5 修订**：P4 / P5 / P6 不迁移（§6 各节决策记录），原三阶段验收项中属于自研实现的 Live 脚本转为常驻回归，并入 P7。

## 9. 文档与后续

- 实施计划：另写 `docs/superpowers/plans/2026-07-22-agentscope-2-upgrade.md`（writing-plans）
- 本升级**不**修改 `docker/mysql/init` 作为前置；可选后续删除未用 `react_pause_checkpoint` 列不阻塞主线
- 索引：挂入 `docs/superpowers/specs/README.md`、必要时 `implementation-plan.md` 增「AS2 升级」缺口行

## 10. 已锁定决策摘要

1. 切分：**方案 1 兼容桥先行**，P0→P7  
2. P0 peer-collab：**允许顺序降级 (A)**  
3. AgentState：**Redis-only · TTL 7 天 · 不改 MySQL 表**  
4. `sessionId` = `assistantMessageId`（**消息级推理现场**，§4.1a；并发防护靠 GenerationJob/epoch，不依赖 AS 串行门）  
5. MySQL `steps`/问答与 AgentState **正交**  
6. 不用 AS Plan Mode 替换 Plan-Workflow  
7. 每阶段：改 → 回归 → 前端真请求通过 → 再继续  
8. **Agent 载体：统一 HarnessAgent 单例（§3.1，P0 即定型，避免 P2→P3 二次迁移）**  
9. **P0 处理 2.0 全部 7 类删除项**（不止 MsgHub，§P0 清单）  
10. **每阶段带同名 feature flag 作回退单元（§7.5），flag 全保留至 P7 统一拆**  
11. **反应式 hub：保留自研 `ExpertHubEngine` 决策逻辑（G-a 路径 1），P0 spike 验证**  
12. **子取消 / 沙箱取消 UX 不可降级**（G-b / G-c）：原生粒度不足时保留自研取消适配层  
13. **每阶段强制回滚测试**（§7.5a）：三段式（正向→回滚→回切）+ 分阶段专项断言 + 脏数据清零，回滚脚本红一个即整阶段红灯  
14. **P4 / P5 / P6 不迁移（E5，2026-07-25）**：spawn_subagent / 沙箱六工具+HITL / peer 反应式 hub **保留全栈自研**。理由：官方原生能力覆盖的是执行内核，而 Sunshine 产品承诺（单独取消不 bump epoch、对话级沙箱容器共享、editDiff 抽屉、六工具 schema、`writeHitlMode` 三态、sha256 审计、反应式选人）全在外壳，占比 80%+；且对话级隔离粒度、「仅首次确认」语义官方无等价物，强迁破坏产品承诺。G-b / G-c 的「适配层」定性相应转为**永久产品实现**。2.0 净收益聚焦 P2（单例 + stateStore 自动保存 + 优雅停机）与 P3（TaskList 状态随 checkpoint 恢复）。原 P4/P5/P6 Live 脚本转为常驻回归并入 P7。
