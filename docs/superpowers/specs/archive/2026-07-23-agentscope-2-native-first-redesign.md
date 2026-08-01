# AgentScope Java 2.0 升级路线重设计（原生优先 · 单路径 · 无 1.0.8 遗产）

> **状态**：**已完成（P0–P3 + P7，2026-07-26）**；P4/P5/P6 经 E5 评审不迁移
> **日期**：2026-07-23（2026-07-27 收口更新）
> **关联**：取代 `2026-07-22-agentscope-2-upgrade-design.md` 的 P1-P7 部分（P0 已完成，不变）；实施记录见 `../plans/archive/2026-07-23-agentscope-2-native-first-redesign.md`（已归档）
> **前置**：P0 ✅（tag `as2-p0-done`）· HarnessAgent spike ✅（`2026-07-23-harness-agent-spike.md`）

## 1. 背景与动因

原 spec（`2026-07-22-agentscope-2-upgrade-design.md`）采用「兼容桥先行 + 双路径 feature flag + P7 清桥」策略。实施 P1 时发现两个根本问题：

1. **Hook 已 `@Deprecated(forRemoval=true, since="2.0.0")`**：整个 `io.agentscope.core.hook` 包（`Hook`/`HookEvent`/`PreReasoningEvent`/`PostReasoningEvent`/`ReasoningChunkEvent`/`PreActingEvent`/`PostActingEvent` 等）和 `stream()` API 均标注 forRemoval，下个 minor 版本移除。原 spec 的「Hook 桥 + LegacyHookDispatcher」是注定要删的兼容层，保留它违背「避免以后难升级」。
2. **HarnessAgent 实际可用**：P0-5 spike 曾误判「HarnessAgent 不存在」，实为漏检独立 artifact `io.agentscope:agentscope-harness:2.0.0`（spike 已修正）。spec §3.1「统一 HarnessAgent 载体」锁定成立，P2-P5 原生能力可用。

用户要求：**不遗留 1.0.8，用 2.0 原生能力（middleware + streamEvents + HarnessAgent），避免以后难升级。**

## 2. 已锁定决策（brainstorming 9 项）

| # | 决策点 | 选择 |
|---|--------|------|
| 1 | 范围 | 重新审视整个 P1-P7 路线 |
| 2 | 载体 | P1 就迁到 HarnessAgent（引入 harness 依赖） |
| 3 | 策略 | 稳健派：flag 作回退单元但不双轨 |
| 4 | 阶段 | 保持 P1-P7 七阶段（P1 变重） |
| 5 | 回滚标准 | 行为等价（phase/label/正文一致，允许 id 不同） |
| 6 | flag 生命周期 | 每阶段结束删 flag + 删旧实现，git revert 回滚 |
| 7 | peer | P1 只迁 ReAct 主路径，peer 在 P6 迁 |
| 8 | workflow | P1 覆盖 workflow agent 节点 |
| 9 | P1 回滚单元 | 单一回滚单元（git revert 回滚） |

## 3. 总体策略

**原生优先 · 单路径 · 每阶段删旧实现**。废除原 spec 的「双路径 flag + P7 清桥」，改为每个能力切换即删旧自研实现，回滚靠 `git revert <phase-commit>`，不靠运行时双路径。

**载体定型（P1 即落）**：引入 `io.agentscope:agentscope-harness:2.0.0`，ReAct 主路径载体从 `ReActAgent` 迁到 `HarnessAgent`。后续 P2-P5 在同一载体上逐阶段启用原生能力，载体不再变（避免 P2->P3 二次迁移，spec §3.1 原则保留）。

**替代关系**：

| 1.0.8 自研 / 已废弃 API | 2.0 原生替代 | 阶段 |
|------------------------|-------------|------|
| `io.agentscope.core.hook.*`（forRemoval） | `MiddlewareBase`（五阶段洋葱） | P1 |
| `agent.stream()`（forRemoval） | `agent.streamEvents()`（28 类 AgentEvent） | P1 |
| `ReActAgent` 载体 | `HarnessAgent`（harness artifact） | P1 |
| `ProcessingStepHook`（Hook 桥） | `ProcessingStepMiddleware` | P1 |
| ReAct 软续跑 `retainIntentStepsOnly` | `stateStore` + `interrupt` | P2 |
| `AutoContextHook`（2.0 已删） | `CompactionConfig` | P2 |
| `ManageTasksTool` / TaskBoard | `enableTaskList` + `TodoTools` | P3 |
| `SpawnSubagentTool` | 声明式 `.subagent()` | P4 |
| 自研沙箱执行内核 | `filesystem(SandboxFilesystemSpec)` | P5 |
| 自研 ReAct HITL | `permissionContext` + `RequireUserConfirmEvent` | P5 |
| peer 顺序桥 + `ExpertSpeakHook` | peer 迁 streamEvents + middleware + 反应式恢复 | P6 |

**不可替代（继续自研）**：Plan-Workflow / Studio、静态 Workflow 检查点、路由与 Prompt Catalog、peer 产品层（Catalog/`$`/Synthesizer）、RAG、tool-manager、审计、Timeline/SSE 外壳、Workflow 节点 HITL（spec §P5 明确）。

## 4. 阶段设计

### P1 - 载体迁移 + Hook->Middleware + streamEvents（一次性）

**范围**：三件事原子提交、原子回滚（单一回滚单元）。

#### 4.1.1 载体迁移（ReActAgent -> HarnessAgent）

- `pom.xml` dependencyManagement 加 `agentscope-harness`；`orchestrator/pom.xml` 加引用
- `ReActAgentFactory.create`：builder 链从 `ReActAgent.builder()...build()` 改为 `HarnessAgent.builder()...build()`。`.hook(stepHookFactory.forBridge(bridgeId))` 改为 `.middleware(processingStepMiddlewareFactory.forBridge(bridgeId))`，其余（`.name`/`.sysPrompt`/`.model`/`.toolkit`/`.maxIters`/`.stateStore`）不变（HarnessAgent Builder 全兼容，spike 已验证）
- `ExpertPeerAgentFactory` **本阶段不改**（peer 在 P6 迁），其 `ExpertSpeakHook` 保留至 P6

#### 4.1.2 Hook -> Middleware（ProcessingStepHook -> ProcessingStepMiddleware）

`ProcessingStepHook implements Hook` 重写为 `ProcessingStepMiddleware implements MiddlewareBase`。职责映射：

| 原 Hook 事件 | 新 Middleware 钩子 | 驱动行为 |
|-------------|-------------------|---------|
| `PreReasoningEvent` | `onReasoning` 入口（`next.apply` 之前） | `session.beginReasoningRound()`（开 think 步） |
| `PostReasoningEvent` | `onReasoning` 出口（`next.apply` 之后） | `session.endReasoningRound()` + TaskBoard 占位 |
| `ReasoningChunkEvent`（think+content delta） | **不在 middleware**，由 streamEvents 事件驱动 | EventMapper 负责 |
| `PreActingEvent` | `onActing` 入口 | 开 tool 步 + 取消注册 + sandbox 文案 |
| `PostActingEvent` | `onActing` 出口 | 闭 tool 步 + catalog summary + editDiff |

**职责正交**：middleware 只负责步骤结构（边界 + metadata + 取消 + editDiff），不负责流式 delta（delta 由 streamEvents 事件经 EventMapper 驱动）。无双写。

#### 4.1.3 stream() -> streamEvents() + EventMapper 接入

`ReActAgentRuntime.startReActStream` 改造：
- `agent.stream(inputs, options)` 改为 `agent.streamEvents(inputs)`
- legacy `mapAgentEvent(Event, session, ...)` 删除，改用 `runtime.AgentScopeEventMapper.mapAgentEvent(AgentEvent, messageId)`
- EventMapper 扩展：`TextBlockDeltaEvent` delta 经 `session.ingestStreamingContentDelta()` 走 ContentSegmentCoordinator（非裸 `StreamToken.content`），与 legacy 正文段协调一致
- EventMapper 的 `ToolCallStartEvent`/`ToolCallEndEvent` **不产出 step token**（tool 步由 middleware `onActing` 统一驱动，避免双写）
- `drainHookTokens(hookQueue)` 保留：middleware 的 `onReasoning`/`onActing` 产出仍经 `StepEventBridge.emit` 路由到 hookQueue，Runtime 仍 drain（保证 think/tool 步结构 token 不丢）

#### 4.1.4 P1 删除项（合入时删）

- `ProcessingStepHook.java` + `ProcessingStepHookFactory.java`
- legacy `agent.AgentScopeEventMapper.map(Event, ...)`
- `ReActAgentRuntime` 中 `agent.stream(inputs, options)` 路径
- `as2.streamEvents` flag（不再双路径）

**不改**：Timeline V2 契约、Catalog、Plan checkpoint、沙箱产品层、MySQL DDL、peer 路径。

**闸门**：编译绿 + 无 `io.agentscope.core.hook`/`agent.stream(`/`LegacyHookDispatcher` 残留 + ReAct Chat（F1）+ workflow agent 节点（Plan DAG）+ spawn SUB 各一轮前端真请求 + `verify_rollback_p1.py` 行为等价断言 + 删除项零残留。

### P2 - 原生 checkpoint/resume + CompactionConfig

- `HarnessAgentHolder` 单例（按 toolsetKey 缓存，spec §3.1）
- Redis `AgentStateStore` TTL=7d（sessionId=assistantMessageId，spec §4.1a）
- `GenerationJob.cancel` -> `agent.interrupt(ctx)`；续走 `streamEvents(inputs, ctx)` 恢复
- `CompactionConfig` 替代已删 AutoContextHook（阈值对标 `MemoryProperties.AutoContext`）
- 删 `retainIntentStepsOnly` 软续跑；删 P1 残留 `as2.reactCheckpoint` flag
- **闸门**：`verify_react_checkpoint_live`（新建）+ 前端停->「继续执行」+ `verify_rollback_p2_checkpoint`

### P3 - TaskList 替换 TaskBoard

- `HarnessAgent.Builder.enableTaskList(true)` + `TodoTools` + `TaskReminderMiddleware`
- 删 `ManageTasksTool` 主路径注册
- Timeline 仍投影单一 `tasks` 步（复用 `TaskBoardStepLabelService`，前端零改）
- 删 `as2.tasklistNative` flag
- **闸门**：TaskBoard Live（改断言）+ 前端任务卡 + `verify_rollback_p3_tasklist`

### P4 - Harness Subagent 替换 spawn

- 前置 spike：HarnessAgent 异步 subagent（`timeout_seconds=0`）下按 `task_id` 单独 cancel 是否 bump 父 epoch（G-b）
- `.subagent(SubagentDeclaration...)`；`SpawnSubagentTool` 改薄封装或删除
- 主卡 `subagent-*` / 抽屉字段不变；单独取消不 bump epoch（原生不足时保留 `SpawnRunRegistry` 作取消适配层，不算双轨）
- 删 `as2.subagentNative` flag
- **闸门**：`verify_spawn_subagent_live`（含单独取消）+ 前端子卡/取消 + `verify_rollback_p4_subagent`

### P5 - Workspace 沙箱 + Permission HITL

- `.filesystem(SandboxFilesystemSpec)` 替换现网沙箱执行内核；取消入口/SSE `lifecycle=paused`/`summary.after=已取消`/detail 保留 command 全不变（G-c）
- `.permissionContext(...)` + `RequireUserConfirmEvent` 替换自研 ReAct HITL；Workflow 节点 HITL 仍自研
- 删 `as2.sandboxWorkspace`/`as2.hitlPermission` flag
- **闸门**：sandbox + hitl Live + `verify_sandbox_tool_cancel_live` + 前端沙箱抽屉/写确认 + `verify_rollback_p5_sandbox`

### P6 - peer-collab 正式化

- `ExpertPeerAgentFactory` 迁 HarnessAgent + streamEvents + middleware（清 `ExpertSpeakHook`）
- `ExpertHubEngine.invokeAgent` 从两阶段（`call().block()` + `expertSpeakStreamer`）合并为单阶段 `streamEvents`（prompt 合并 gather+speak，P0-5 spike §5）
- 反应式选人恢复（`selectReactiveSpeakers`，P0-4 已保留）
- 删 P0 顺序桥标记 `AS2_P0_PEER_SEQUENTIAL`；删 `as2.peerReactive` flag
- **闸门**：`verify_peer_collab_live` + `verify_expert_consultation_live` + 前端 `$` 完整路径 + `verify_rollback_p6_peer`

### P7 - 收口

- 已无桥可清（每阶段已自清），P7 变轻
- 更新 CLAUDE.md（删「勿升 2.0」；写明续跑依赖 Redis StateStore TTL=7d）
- 全量回归包 + 四模式前端抽检 + 全量回滚脚本最终回归
- **闸门**：全量 Live 绿 + 全量 e2e 绿 + 四模式人工抽检

## 5. 回滚规范

**机制**：`git revert <phase-commit>`，不靠运行时双路径。每阶段合并前跑 `verify_rollback_p<n>.py` 验证三段：

1. **正向**：当前代码（新路径）-> 跑该阶段核心 Live -> 确认新行为生效
2. **回滚**：`git revert <phase-commit>` -> 跑 P0 基线 Live -> 确认回退到上一阶段行为
3. **回切**：`git revert --abort` -> 跑同一套 Live -> 确认新行为恢复，无脏数据残留

**验证标准**：行为等价（非逐字节）
- 步骤序列：phase + label 序列一致（允许 step id 时间戳不同）
- 正文：拼接文本一致（`"".join(body)`，允许 SSE delta 切分粒度不同）
- 工具调用：次数 + catalog tool name 序列一致

**脏数据清零**：每次回滚后检查 Redis（按 key 前缀清 `agentscope:state:*` 等）、MySQL `chat_message.steps` 无半写入、GenerationJob Redis stream 无孤立 generationId。

**门槛**：回滚脚本与正向 Live 同等级，红一个即整阶段红灯。

## 6. 验收总表

| 阶段 | 自动化 | 前端真请求 |
|------|--------|-----------|
| P1 | 编译 + 删除项零残留 + `verify_rollback_p1`（行为等价） | ReAct（F1）+ workflow agent 节点 + spawn SUB |
| P2 | `verify_react_checkpoint_live` + `verify_rollback_p2` | 停->「继续执行」+ TTL 降级 |
| P3 | TaskBoard Live（改断言）+ `verify_rollback_p3` | 任务卡（F1）|
| P4 | spawn Live（含单独取消）+ `verify_rollback_p4` | 子卡/取消（S1）|
| P5 | sandbox+hitl Live + `verify_sandbox_tool_cancel_live` + `verify_rollback_p5` | 沙箱抽屉 + 写确认 |
| P6 | peer/expert Live（反应式恢复）+ `verify_rollback_p6` | `$` 完整路径（E1）|
| P7 | 全量回归包 + 全量回滚最终回归 | 四模式抽检 |

## 7. 风险与缓解

| 风险 | 缓解 |
|------|------|
| middleware 钩子入口/出口与 Hook 语义非 1:1（`onReasoning` 包裹整轮 vs Pre/Post 两个事件） | P1 单测钉死 think 步开闭时机；`onReasoning` 入口开 think、出口（`next.apply` 的 `doFinally`）闭 think |
| P1 改动量大、风险集中 | 单一回滚单元 + 全量回归（ReAct/workflow/spawn）+ 行为等价断言 |
| streamEvents delta 切分粒度与 legacy Hook 不同 | 回滚标准放宽为「正文拼接一致」非「SSE 逐字节」 |
| HarnessAgent streamEvents 不转发 subagent 事件（release notes gap） | P4 spike 验证；若不足，SUB 路径暂留 `stream()` 至 AS 补齐 |
| peer prompt 合并（gather+speak）改变发言质量 | P6 live 对比 4.7.3 反应式基线；不可接受则保留两阶段 |

## 8. 与原 spec 的关系

- **取代**：原 `2026-07-22-agentscope-2-upgrade-design.md` 的 P1-P7 部分
- **保留**：原 spec 的 §1-§2（背景/目标）、§3.1（HarnessAgent 载体）、§4（AgentState Redis-only TTL=7d）、§7.5a（回滚测试规范框架，机制改为 git revert）
- **P0 不变**：已完成（tag `as2-p0-done`），P0 的顺序桥/AgentStateStore 占位等在后续阶段清理
- **spike 修正**：P0-5 的「HarnessAgent 不存在」已由 `2026-07-23-harness-agent-spike.md` 推翻
