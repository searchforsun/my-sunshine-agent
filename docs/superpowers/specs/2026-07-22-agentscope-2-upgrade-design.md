# AgentScope Java 2.0 分阶段升级与原生能力采纳

> **状态**：已评审（brainstorming 锁定 + 一轮完善性评审）· 待实施计划  
> **日期**：2026-07-22（2026-07-23 完善性修订）  
> **关联**：现网 AS **1.0.8** · ReAct 软续跑 · Plan/Workflow `WorkflowCheckpoint`（3.9.5）· TaskBoard（4.7.5）· spawn_subagent（4.7.6）· 沙箱（4.5）· peer-collab  
> **前置决策**：方案 **1（兼容桥先行）** · P0 peer-collab **允许顺序降级 (A)** · AgentState **Redis-only · TTL 7 天 · 不改 MySQL 表** · **统一 HarnessAgent 载体**（§3.1）

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

**P0 peer-collab**：`MsgHub` 在 2.0 已删除 → 允许**顺序发言降级**（功能可用、弱于现网反应式 hub），P6 正式恢复/对齐。

### 3.1 Agent 载体选型（E3 修订，P0 前必须定调）

AS 2.0 为**双层架构**：

| 层 | 能力 | 覆盖本方案阶段 |
|----|------|----------------|
| `ReActAgent` | 无状态推理 · `stateStore`+`interrupt` · `streamEvents` | P2 已够用 |
| `HarnessAgent`（上层） | 在 ReAct 之上加 Workspace / **Compaction** / **TaskList** / **Subagent** / Channel | P3 / P4 / P5 必需 |

**决策：ReAct 主路径统一到 `HarnessAgent` 单例载体（P0 即定型，不在 P2→P3 之间二次迁移载体）。**

理由：若 P2 先在 `ReActAgent` 上做 checkpoint，P3 TaskList / P4 Subagent / P5 Workspace 又必须迁到 `HarnessAgent`，等于 P2→P3 之间再迁一次 Agent 载体，违背"避免大爆炸 / 禁双轨"原则。一次到位到 HarnessAgent，P2 只用其子集能力（stateStore/interrupt/streamEvents），后续阶段逐步启用上层能力，载体不变。

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
| `AutoContextHook`（hook 包，2.0 软弃用） | **HarnessAgent `CompactionConfig`**（`triggerMessages`/`keepMessages`，可独立小模型 `.model()`/`.prompt()`） | P2 起对齐，禁双轨 |
| TaskBoard `manage_tasks` | `enableTaskList` + TodoTools | P3 |
| `spawn_subagent` | Harness Subagent + distributedStore | P4 |
| 沙箱执行内核（部分） | Workspace / DockerFilesystemSpec | P5 |
| ReAct 写工具 HITL | Permission + `RequireUserConfirmEvent` | P5 |
| 每请求 `new ReActAgent` | 无状态 HarnessAgent 单例 + `RuntimeContext` | P2 |

> 注：Sunshine 现网用的是 `AutoContextHook`（`io.agentscope.core.memory.autocontext`），**不是** `AutoContextMemory`；2.0 原生替代是 `CompactionConfig`，且**仅在 HarnessAgent 上可用**——这正是 §3.1 统一 HarnessAgent 载体的直接动因。

**不可替代（继续自研）**：Plan-Workflow / Studio、静态 Workflow 检查点、路由与 Prompt Catalog、peer 产品层（Catalog/`$`/Synthesizer）、RAG、tool-manager、审计、Timeline/SSE 外壳。

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

### P3 — TaskList 替换 TaskBoard

**范围**：`.enableTaskList(true)` + TodoTools + TaskReminderMiddleware；下线 `manage_tasks` 主路径；Timeline 仍投影为单一 `tasks` 步（前端尽量零改）

**闸门**：更新后的 TaskBoard Live + 前端任务卡

### P4 — Harness Subagent 替换 spawn

**范围**：声明式 Subagent；薄封装或下线 `SpawnSubagentTool`；主卡 `subagent-*` / 抽屉字段尽量保持；单独取消不得 bump 整轮 stream epoch；`distributedStore`（Redis）

**单独取消映射（G-b 钉死）**：现网 `SpawnRunRegistry.cancel` 基于 interrupt、不 bump epoch。迁移后必须验证：异步模式（`timeout_seconds=0`）下能否按 `task_id` **单独 cancel 子 agent** 而不触发父 Agent 的 State 落盘中断。P4 开始前先用一个 spike（半日）在 HarnessAgent 上实测；若原生不支持，**保留 `SpawnRunRegistry` 作为子取消适配层**（不算双轨，属于外壳 UX 保留），不得为迁就原生取消语义而放弃不 bump epoch 的 UX 承诺。

**闸门**：`verify_spawn_subagent_live`（含单独取消）+ 前端

### P5 — Workspace 沙箱 + Permission HITL

**范围**：执行内核迁 Workspace/Docker；保留抽屉 / diff / `writeHitlMode` / 取消 UX；Catalog `require_confirmation` → Permission 事件 → 现有确认 UI；Workflow 节点 HITL 可仍自研

**沙箱取消兼容（G-c 钉死）**：现网 4.5.7 `CancellableToolRunRegistry` + sandbox kill 提供 `sandbox__exec/grep/glob` 的细粒度取消（hover 圆钮 / 主行「已取消」/ 同族预算 3）。迁 Workspace 后：执行后端换成 DockerFilesystemSpec，但**取消入口、SSE `lifecycle=paused`、`summary.after=已取消` 文案、detail 保留 command/pattern** 全部不变。P5 闸门必须含 `verify_sandbox_tool_cancel_live` 全绿；若 Workspace 原生取消粒度不足，保留 `CancellableToolRunRegistry` 作为取消适配层（同 G-b 原则）。

**闸门**：沙箱 Live + ReAct HITL Live + **`verify_sandbox_tool_cancel_live`** + 前端

### P6 — peer-collab 正式化

**范围**：用 subagent/middleware/显式轮次恢复反应式语义；删除 P0 顺序桥；保留 expert Catalog / `$` / 前端专家步

**反应式 hub 技术路径（G-a 钉死，P0 期间即需 spike 验证）**：AS 2.0 删 `pipeline` 包后无现成圆桌原语。恢复「第 2 轮起反应式选人」的候选路径，按优先级：

1. **保留自研 `ExpertHubEngine` 选人逻辑**（min/max 轮次 + continue 判断 + 反应式选人），仅把「专家 Agent 调用」从 MsgHub 换成对每专家 HarnessAgent 的 `streamEvents`；反应式 hub 属**不可替代产品层**（§5 已列 peer 产品层自研），不强行用 AS 原生多智能体替代。
2. 若坚持原生对齐：用 Harness subagent + 显式轮次编排近似，但「反应式选人」仍需自研决策函数。

**P0 spike 出口**：P0 顺序桥落地时，用半日 spike 验证路径 1 在 2.0 下编译/流式可行，结论写入实施计划。**禁止**到 P6 才发现反应式语义无法恢复——若路径 1 不可行，P6 降级为「顺序 + 自研轮次控制」，并在文档明示对 4.7.3 反应式特性的取舍。

**闸门**：`verify_peer_collab_live` + `verify_expert_consultation_live` + 前端

### P7 — 清桥收口

**范围**：删除 Legacy Hook/Memory/顺序桥/双实现开关；更新 `CLAUDE.md`（取消「勿升 2.0」；写明 ReAct 续跑依赖 Redis StateStore TTL=7d）；全量相关 Live + 四模式前端抽检

## 7. 风险与错误处理

| 风险 | 缓解 |
|------|------|
| MsgHub 删除导致 P0 编译不过 | 顺序桥为硬前置 |
| P1 事件映射双写正文 | 单一适配器；沿用 ContentSegment 规则 |
| State 与 steps 续跑策略冲突 | P2 禁止清空 steps；State 缺失则 regenerate |
| 双轨 TaskBoard/spawn/沙箱 | 阶段结束拆桥；禁止两套执行器并行 |
| 误用 Plan Mode | 文档与评审明确禁止 |
| HarnessAgent 未定型导致 P2→P3 二次迁移 | §3.1 前置定型，P0 即落 HarnessAgent 骨架 |
| P6 反应式 hub 无法恢复 | P0 spike 验证路径 1（G-a），提前暴露 |
| 消息级 sessionId 并发失控 | §4.1a：靠 GenerationJob + stream epoch 防护，不依赖 AS 串行门 |

## 7.5 回滚策略（G-d，按阶段）

| 阶段 | 回退单元 | 回退动作 |
|------|----------|----------|
| P0 | jar 版本 | 回 `agentscope.version` 至 1.0.8 + revert P0 commit |
| P1 | jar + 事件适配器开关 | `agent.events.legacy-hook=true` 切回 hook 路径（P1 期间保留双路径开关，P7 才拆） |
| P2 | feature flag | `agent.react.checkpoint.enabled=false` → 回 `retainIntentStepsOnly` 软续跑（代码保留至 P7） |
| P3 | feature flag | `agent.tasklist.native=false` → 回 `manage_tasks` 主路径 |
| P4 | feature flag | `agent.subagent.native=false` → 回 `SpawnSubagentTool` |
| P5 | feature flag | `agent.sandbox.workspace=false` → 回现网沙箱执行内核；`agent.hitl.permission=false` → 回自研 HITL |
| P6 | feature flag | `agent.peer.reactive=false` → 回 P0 顺序桥 |

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
| P3 | `verify_rollback_p3_tasklist.py` | TaskList ↔ `manage_tasks` 切换，任务卡 UI 数据一致，`tasks` 步投影不回退 |
| P4 | `verify_rollback_p4_subagent.py` | 原生 subagent ↔ `SpawnSubagentTool` 切换，**单独取消**在两条路径下都不 bump epoch |
| P5 | `verify_rollback_p5_sandbox.py` | Workspace ↔ 现网沙箱切换，`verify_sandbox_tool_cancel_live` 两条路径全绿；Permission ↔ 自研 HITL 切换确认 UI 一致 |
| P6 | `verify_rollback_p6_peer.py` | 反应式 ↔ 顺序桥切换，`verify_peer_collab_live` 主路径 + `$` 绑定两条路径全绿 |

**脏数据清零检查（每次回滚后必跑）**：Redis `FLUSHDB` 不可取——按 key 前缀清：`agentscope:state:*` / `agentscope:tasklist:*` / `agentscope:subagent:*`；MySQL 检查 `chat_message.steps` 无半写入；GenerationJob Redis stream 无孤立 generationId。

**门槛**：回滚脚本与正向 Live 同等级，**红一个即整阶段红灯**，禁止带病进入下一阶段。

## 8. 验收总表（按阶段）

| 阶段 | 自动化 | 前端真请求 |
|------|--------|------------|
| P0 | 编译 + 核心单测 + 基础 demo + **7 类删除项零残留** + 反应式 hub spike 结论 + `verify_rollback_p0_compile` | ReAct 一轮对话；peer 降级可用 |
| P1 | Timeline/事件相关单测 + **适配层性能基线达标** + `verify_rollback_p1_events` | 步骤 + 流式正文 |
| P2 | `verify_react_checkpoint_live`（新建） + `verify_rollback_p2_checkpoint` | 停→「继续执行」 |
| P3 | TaskBoard Live（改断言） + `verify_rollback_p3_tasklist` | 任务卡 |
| P4 | spawn Live（含**单独取消不 bump epoch**） + `verify_rollback_p4_subagent` | 子卡/取消 |
| P5 | sandbox + hitl Live + **`verify_sandbox_tool_cancel_live`** + `verify_rollback_p5_sandbox` | 沙箱抽屉 + 写确认 |
| P6 | peer/expert Live（**反应式选人恢复**） + `verify_rollback_p6_peer` | `$` 完整路径 |
| P7 | 回归包 + feature flag 全拆 + **全量回滚脚本最终回归** | react/workflow/plan/peer 抽检 |

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
