# AgentScope Java 2.0 分阶段升级与原生能力采纳

> **状态**：已评审（brainstorming 锁定）· 待实施计划  
> **日期**：2026-07-22  
> **关联**：现网 AS **1.0.8** · ReAct 软续跑 · Plan/Workflow `WorkflowCheckpoint`（3.9.5）· TaskBoard（4.7.5）· spawn_subagent（4.7.6）· 沙箱（4.5）· peer-collab  
> **前置决策**：方案 **1（兼容桥先行）** · P0 peer-collab **允许顺序降级 (A)** · AgentState **Redis-only · TTL 7 天 · 不改 MySQL 表**

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

## 4. AgentState 持久化约定（已锁定）

### 4.1 决策

| 项 | 约定 |
|----|------|
| 存储 | **仅 Redis**（`RedisAgentStateStore` / DistributedStore 等价配置） |
| TTL | **7 天**（604800s）；过期后不可检查点续跑 |
| MySQL | **不改表**；不把 AgentState 写入 `chat_message` / `steps` |
| sessionId | **`assistantMessageId`**（消息级续跑；禁止用 `conversationId` 以免多轮串态） |
| 与 Timeline | **正交**：MySQL `steps` = UI；Redis AgentState = 推理上下文 |

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
| `AutoContextMemory` | Harness 上下文压缩 / Memory middleware | P2 起对齐，禁双轨 |
| TaskBoard `manage_tasks` | `enableTaskList` + TodoTools | P3 |
| `spawn_subagent` | Harness Subagent + distributedStore | P4 |
| 沙箱执行内核（部分） | Workspace / DockerFilesystemSpec | P5 |
| ReAct 写工具 HITL | Permission + `RequireUserConfirmEvent` | P5 |
| 每请求 `new ReActAgent` | 无状态单例 + `RuntimeContext` | P2 |

**不可替代（继续自研）**：Plan-Workflow / Studio、静态 Workflow 检查点、路由与 Prompt Catalog、peer 产品层（Catalog/`$`/Synthesizer）、RAG、tool-manager、审计、Timeline/SSE 外壳。

## 6. 分阶段技术方案

每阶段出门闸门统一为：

1. 相关单测通过  
2. 指定 `verify_*` / demo Live  
3. Chat **前端真请求**（该阶段涉及的执行模式）人工确认  

闸门未过 → 禁止开下一阶段。

### P0 — 依赖可编译可跑

**范围**

- `agentscope.version` → 2.0.x；引入 `agentscope-extensions-model-openai` 等
- `OpenAIChatModel` 包迁移；Gateway `/v1` 对接保持
- Factory：以可运行为准；可用弃用桥（Hook/Memory）或占位 Store，**本阶段不宣称续跑**
- `ExpertHubEngine`：去掉 `MsgHub`，改为**顺序**调用专家；广播上下文改为显式 `contextBlocks`；标记 `AS2_P0_PEER_SEQUENTIAL`

**不改**：Timeline 契约、Catalog、Plan checkpoint、沙箱产品层、MySQL DDL

**闸门**：编译；基础 ReAct Chat 前端；peer-collab `$` 能出专家步（不要求与现网轮次完全一致）

### P1 — 事件契约 `streamEvents` → Timeline

**范围**

- `ReActAgentRuntime`：迁到 `streamEvents`（或等价）
- `AgentScopeEventMapper`：`AgentEvent` → `StreamToken` / Timeline；正文仍经 `ContentSegmentCoordinator`；**禁止**截断/摘要模型输出
- Hook 可暂留 `LegacyHookDispatcher`，目标减少对旧 `Event` 硬依赖

**数据流**：`streamEvents` → EventAdapter → `StepEventBridge` / Timeline → `GenerationJob` → SSE

**闸门**：ReAct / Workflow agent 节点步骤与流式正文前端一致

### P2 — 原生 ReAct checkpoint / resume

**范围**

- 单例（或池化）Agent + `RuntimeContext(userId, sessionId=assistantMessageId)`
- Redis `AgentStateStore`，**TTL=7 天**，key 前缀与 GenerationJob 分离，租户可感知前缀
- 停止：`GenerationJob.cancel` → `interrupt(ctx)`，依赖框架落盘
- 续跑：有可用 State →「继续执行」；**废除** ReAct 路径的 `retainIntentStepsOnly` 清空重跑
- 前端：`resolveResumeMode` 在有 checkpoint 时对 ReAct 返回 `checkpoint`；保留已有 steps，新事件 append
- MySQL **零 DDL**

**中断在 tool 执行中**：以 AS 语义为准，Live 用例钉死「重做 vs 跳过」预期。

**闸门**：新 Live（如 `verify_react_checkpoint_live.py`）；前端停→续步骤连续、已完成 tool 不无故整轮重来

### P3 — TaskList 替换 TaskBoard

**范围**：`.enableTaskList(true)` + TodoTools + TaskReminderMiddleware；下线 `manage_tasks` 主路径；Timeline 仍投影为单一 `tasks` 步（前端尽量零改）

**闸门**：更新后的 TaskBoard Live + 前端任务卡

### P4 — Harness Subagent 替换 spawn

**范围**：声明式 Subagent；薄封装或下线 `SpawnSubagentTool`；主卡 `subagent-*` / 抽屉字段尽量保持；单独取消不得 bump 整轮 stream epoch；`distributedStore`（Redis）

**闸门**：`verify_spawn_subagent_live`（含单独取消）+ 前端

### P5 — Workspace 沙箱 + Permission HITL

**范围**：执行内核迁 Workspace/Docker；保留抽屉 / diff / `writeHitlMode` / 取消 UX；Catalog `require_confirmation` → Permission 事件 → 现有确认 UI；Workflow 节点 HITL 可仍自研

**闸门**：沙箱 Live + ReAct HITL Live + 前端

### P6 — peer-collab 正式化

**范围**：用 subagent/middleware/显式轮次恢复反应式语义；删除 P0 顺序桥；保留 expert Catalog / `$` / 前端专家步

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

## 8. 验收总表（按阶段）

| 阶段 | 自动化 | 前端真请求 |
|------|--------|------------|
| P0 | 编译 + 核心单测 + 基础 demo | ReAct 一轮对话；peer 降级可用 |
| P1 | Timeline/事件相关单测 | 步骤 + 流式正文 |
| P2 | `verify_react_checkpoint_live`（新建） | 停→「继续执行」 |
| P3 | TaskBoard Live（改断言） | 任务卡 |
| P4 | spawn Live | 子卡/取消 |
| P5 | sandbox + hitl Live | 沙箱抽屉 + 写确认 |
| P6 | peer/expert Live | `$` 完整路径 |
| P7 | 回归包 | react/workflow/plan/peer 抽检 |

## 9. 文档与后续

- 实施计划：另写 `docs/superpowers/plans/2026-07-22-agentscope-2-upgrade.md`（writing-plans）
- 本升级**不**修改 `docker/mysql/init` 作为前置；可选后续删除未用 `react_pause_checkpoint` 列不阻塞主线
- 索引：挂入 `docs/superpowers/specs/README.md`、必要时 `implementation-plan.md` 增「AS2 升级」缺口行

## 10. 已锁定决策摘要

1. 切分：**方案 1 兼容桥先行**，P0→P7  
2. P0 peer-collab：**允许顺序降级 (A)**  
3. AgentState：**Redis-only · TTL 7 天 · 不改 MySQL 表**  
4. `sessionId` = `assistantMessageId`  
5. MySQL `steps`/问答与 AgentState **正交**  
6. 不用 AS Plan Mode 替换 Plan-Workflow  
7. 每阶段：改 → 回归 → 前端真请求通过 → 再继续  
