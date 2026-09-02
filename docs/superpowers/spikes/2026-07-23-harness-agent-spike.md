# HarnessAgent 可用性 Spike（修正 P0-5 结论）

> **日期**：2026-07-23 · **执行**：AS2 升级路线架构确认 spike · **结论**：**HarnessAgent 存在且可用，spec §3.1 锁定成立**

## 1. 背景

P0-5 spike（`2026-07-23-peer-reactive-hub-spike.md` §2.5）得出结论：「AS 2.0.0 jar 内不存在 `HarnessAgent` 类」，并据此对 spec §3.1（统一 HarnessAgent 单例载体）提出质疑，建议「P2-P5 计划需重新评估载体」。

用户在推进 AS2 升级时选择「先 spike 验证 HarnessAgent 可用性再决定」，本 spike 即为该项验证。

## 2. P0-5 错误根因

P0-5 spike 只检查了两个 jar：

```
$ jar -tf agentscope-core-2.0.0.jar | grep -i harness   # 无结果
$ jar -tf agentscope-2.0.0.jar | grep -i HarnessAgent    # 无结果
```

**遗漏**：`HarnessAgent` 不在 `agentscope-core` 也不在聚合 `agentscope` artifact 中，而在**独立模块 `io.agentscope:agentscope-harness`** 中。这是 AgentScope 2.0 的模块化设计--harness 作为 core 之上的生产工程层，按需引入。

## 3. 验证证据

### 3.1 Maven 中央仓库

```
io.agentscope:agentscope-harness:2.0.0  (latest=release=2.0.0, 2026-07-10 发布)
```

版本序列：`1.1.0-RC1` / `1.1.0-RC2` / `2.0.0-RC1..RC5` / `2.0.0`。GA 版本与 `agentscope` / `agentscope-core` 同步发布。

### 3.2 jar 内类验证

```
$ jar -tf agentscope-harness-2.0.0.jar | grep -iE "HarnessAgent|harness.*Agent"
io/agentscope/harness/agent/                        # 包路径 io.agentscope.harness.agent
io/agentscope/harness/agent/middleware/
io/agentscope/harness/agent/tools/
io/agentscope/harness/agent/memory/
io/agentscope/harness/agent/memory/compaction/
io/agentscope/harness/agent/skill/
io/agentscope/harness/agent/workspace/
io/agentscope/harness/agent/filesystem/
io/agentscope/harness/agent/filesystem/spec/         # SandboxFilesystemSpec 等
io/agentscope/harness/agent/filesystem/sandbox/
io/agentscope/harness/agent/subagent/
io/agentscope/harness/agent/subagent/task/           # TaskRepository
io/agentscope/harness/agent/bus/
...
```

**HarnessAgent 类全名**：`io.agentscope.harness.agent.HarnessAgent`

### 3.3 javap 确认关键 API

**实例方法**（与 spec 各阶段对应）：

| 方法 | spec 阶段 | 用途 |
|------|-----------|------|
| `streamEvents(List<Msg>, RuntimeContext)` 等 6 重载 | P1 | 事件流（与 ReActAgent 同签名，changelog B.4 确认） |
| `interrupt()` / `interrupt(Msg)` | P2 | 停止 + checkpoint |
| `getStateStore()` : `AgentStateStore` | P2 | 续跑状态 |
| `getDistributedStore()` : `DistributedStore` | P4 | subagent 分布式状态 |
| `getSubagentAgentManager()` | P4 | 子 Agent 管理 |
| `setPermissionMode(ctx, PermissionMode)` | P5 | HITL 权限 |
| `enterPlanMode(ctx)` / `exitPlanMode(ctx)` | （spec 禁用 AS Plan Mode，不用） | - |

**Builder 方法**（`HarnessAgent.Builder`）：

| Builder 方法 | spec 阶段 | 对应 spec 计划假设 |
|--------------|-----------|-------------------|
| `.stateStore(AgentStateStore)` | P0/P2 | ✅ P0-3 已用（占位） |
| `.enableTaskList()` / `.enableTaskList(boolean)` | P3 | ✅ spec §P3 |
| `.subagent(SubagentDeclaration)` / `.subagents(...)` | P4 | ✅ spec §P4 |
| `.workspace(Path)` / `.filesystem(SandboxFilesystemSpec)` | P5 | ✅ spec §P5 |
| `.compaction(CompactionConfig)` | P2 | ✅ spec §P2（替代 AutoContextHook） |
| `.distributedStore(DistributedStore)` | P4 | ✅ spec §P4 |
| `.middleware(MiddlewareBase)` / `.middlewares(...)` | 通用 | ✅ TaskReminderMiddleware 等 |
| `.permissionContext(PermissionContextState)` / `.stopOnReject(boolean)` | P5 | ✅ HITL |
| `.taskRepository(TaskRepository)` | P3/P4 | ✅ TaskList 底层 |
| `.asyncToolRegistry(AsyncToolRegistry)` / `.asyncToolTimeout(Duration)` | P4/P5 | 异步工具取消（G-b/G-c） |
| `.model(Model)` / `.model(String)` / `.toolkit(Toolkit)` / `.maxIters(int)` / `.sysPrompt(String)` / `.name(String)` | P0 基础 | ✅ 与 ReActAgent 一致 |
| `.hook(Hook)` / `.hooks(List<Hook>)` | P0 桥 | ✅ LegacyHookDispatcher 桥（P7 拆） |
| `.fromAgent(ReActAgent)` | P2 迁移 | ✅ 从现网 ReActAgent 升级载体 |

### 3.4 官方文档佐证

- GitHub Release v2.0.0（2026-07-10）：「HarnessAgent: Extends ReActAgent through Middleware and Toolkit channels, adding workspace, memory, sandbox, subagents, skills, and plan mode as engineering infrastructure」
- Quickstart：「HarnessAgent is the recommended entry point - it packages workspace, long-term memory, session persistence, subagents, sandboxes... Depending on `agentscope-harness` pulls `agentscope-core` in transitively」
- 依赖声明：`io.agentscope:agentscope-harness:2.0.0`（注意：当前 pom.xml **未引入**此依赖，P0 只引入了 `agentscope` + `agentscope-extensions-model-openai`）

## 4. 对升级计划的影响

### 4.1 spec §3.1 锁定成立

「ReAct 主路径统一到 HarnessAgent 单例载体（P0 即定型）」的决策**可以成立**。P0-5 spike 的质疑源于 jar 检查不完整，现推翻。

### 4.2 P0 载体现状

P0 当前实际载体是 `ReActAgent`（`ReActAgentFactory` / `ExpertPeerAgentFactory` 直接 build `ReActAgent`），**未**引入 `agentscope-harness` 依赖。这与 spec §3.1「P0 即定型 HarnessAgent」有偏差，但不阻塞--P0 闸门已过，ReActAgent 与 HarnessAgent 在 P1 事件路径上签名一致。

### 4.3 P2 是载体迁移点

spec 计划 P2-1（HarnessAgentHolder 单例骨架）即为载体从 `ReActAgent` 迁到 `HarnessAgent` 的落地点。实施 P2 前需：

1. **pom.xml 引入 `agentscope-harness` 依赖**（`orchestrator/pom.xml` + 根 `pom.xml` dependencyManagement）
2. `ReActAgentFactory` / `ExpertPeerAgentFactory` 改为 build `HarnessAgent`（可用 `.fromAgent(reactAgent)` 平滑迁移，或直接重写 builder 链）
3. 验证 HarnessAgent 在 Sunshine 下的编译 + 基础 ReAct 路径（P0 已验证的 ReAct Chat 回归）

### 4.4 P1 不受影响

P1（streamEvents + EventMapper）不依赖 HarnessAgent，`ReActAgent.streamEvents` 已在 P0-5 spike 验证可用。P1 可直接在 ReActAgent 载体上完成，P2 再迁载体。

### 4.5 P3-P5 计划可按原计划推进

`enableTaskList` / `subagent` / `workspace` / `filesystem(SandboxFilesystemSpec)` / `compaction` / `distributedStore` / `permissionContext` 全部在 Builder 中确认，spec §P3/§P4/§P5 的实施假设成立。

## 5. 给 P0-5 spike 文档的勘误

`2026-07-23-peer-reactive-hub-spike.md` §2.5「HarnessAgent 缺口」整节结论错误，应标注：

> **勘误（2026-07-23）**：HarnessAgent 存在于独立 artifact `io.agentscope:agentscope-harness:2.0.0`，非 `agentscope-core` / 聚合 `agentscope`。本 spike 当时只检查了后两者，遗漏了 harness 模块。spec §3.1 锁定成立，详见 `2026-07-23-harness-agent-spike.md`。本节其余关于「路径 1 不依赖 HarnessAgent」的结论仍有效（P6 路径 1 确实用 ReActAgent.streamEvents 即可）。

## 6. 结论

- ✅ `HarnessAgent` 在 AS 2.0.0 GA 存在且可用，位于 `io.agentscope:agentscope-harness:2.0.0`
- ✅ Builder API 完整覆盖 spec P2-P5 所需（stateStore / enableTaskList / subagent / workspace / filesystem / compaction / distributedStore / permissionContext）
- ✅ spec §3.1「统一 HarnessAgent 载体」锁定成立，P0-5 spike 质疑推翻
- ✅ P1 可在 ReActAgent 上完成（不阻塞）；P2 引入 harness 依赖 + 迁载体
- ⚠️ 当前 pom.xml 未引入 `agentscope-harness` 依赖，P2-1 前置任务需补

**给升级路线的决策**：按原 spec 计划推进，P2 是载体迁移点（引入 harness 依赖 + ReActAgent→HarnessAgent），P1 先完成不迁载体。

## 7. 参考

- spec：`docs/superpowers/specs/2026-07-22-agentscope-2-upgrade-design.md` §3.1 / §5
- P0-5 spike（被修正）：`docs/superpowers/spikes/2026-07-23-peer-reactive-hub-spike.md` §2.5
- 官方 Release v2.0.0：https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.0
- Maven Central：https://repo1.maven.org/maven2/io/agentscope/agentscope-harness/
