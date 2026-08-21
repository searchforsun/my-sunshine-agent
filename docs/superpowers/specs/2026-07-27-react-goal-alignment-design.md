# ReAct 目标对齐与重规划 Hook（L2 进度追踪 + L3 动态重规划）

> **阶段**：四 · **任务卡**：4.7.7（候选）
> **状态**：📋 设计评审中（未实现）
> **日期**：2026-07-27 · **v2（2026-08-10）**：吸收原 4.7.8 中仍有观察价值的可选项（§12）；4.7.8 全文已归档
> **前置**：4.7.5 ReAct TaskBoard（原生 `todo_write` + `tasksContext`）、AS 2.0 `TaskReminderMiddleware`、4.5.7 可取消工具（`ToolResultEndEvent.state`）
> **关联**：[2026-06-24-react-taskboard-design.md](archive/2026-06-24-react-taskboard-design.md)（D11）· `ProcessingStepMiddleware` · `AgentExecutionProperties` · [archive/4.7.8](./archive/2026-07-28-harness-loop-enhancement-design.md)

---

## 1. 背景与目标

三层任务规划体系（静态拆解 / 进度追踪 / 动态重规划）在 ReAct 模式的落地现状：

| 层 | 现状 | 缺口 |
|----|------|------|
| L1 静态拆解 | ✅ TaskBoard（`todo_write`）首轮建板 | 无 |
| L2 进度追踪 | 原生 `TaskReminderMiddleware` 每轮重注入任务清单，但**只列状态、不锚定全局目标**；compaction 后模型易漂移、收集无关素材不自知 | 无「当前进展是否服务于最终输出」的显式校验 |
| L3 动态重规划 | 仅 `mode-overlays.react` 提示词引导「超时改参/换工具」；模型可能同参数死循环到 `max-iters` 耗尽 | 无引擎级失败预算与强干预；关键数据持续不可得时无「及时向用户同步」的引导 |

| 目标 | 说明 |
|------|------|
| 目标锚定 | 周期性把**原始用户问题 + 任务清单进度**重新摆到模型面前，要求 think 先对齐目标再行动 |
| 失败预算 | 引擎统计工具失败（同名/同参数指纹），达到阈值注入**强提示**，禁止同参数死循环 |
| 收束引导 | 关键数据持续不可得时，引导模型向用户如实同步现状后收束，而非盲目循环 |
| Timeline 可见 | 失败预算触发在 Timeline 可见（tool 步 after 文案），不新增前端组件 |

**不做**：显式 Replan 节点（违背 D11「TaskBoard 禁止做成 mini-DAG」）；新增 ExecutionMode；对模型输出做截断/过滤；LLM 二次校验调用（成本与延迟不可接受）。

---

## 2. 方案选型

| 方案 | 结论 |
|------|------|
| A. 纯提示词（改 `mode-overlays.react`） | **并行做**：零代码立即生效，但无硬保障，作为 Hook 的语义补充 |
| B. Hook 级引擎兜底（独立 Middleware + Catalog 模板 + 失败预算） | **采用（本设计）** |
| C. 显式 Replan 节点（对齐 plan-workflow `PlanValidator`） | 否：把 ReAct 变成 mini plan-workflow，违背 D11 边界 |

方案 B 的核心判断：

- AgentScope 2.0 `TaskReminderMiddleware` 已验证「**瞬态注入** `<system-reminder>` USER 消息到 `ReasoningInput`」的模式：不落 `AgentState.context`、不参与 compaction、带 `METADATA_SYNTHETIC` 标记。本设计完全复用该模式，**不重复造轮子**。
- 失败检测复用 4.5.7 已验证的 `ToolResultEndEvent.getState()`（`ERROR` / `INTERRUPTED`），**禁止**正文关键字猜测。
- 两个关注点（目标对齐 vs 失败预算）拆分两个 Middleware，职责单一、可独立开关。

---

## 3. 架构

```mermaid
flowchart TB
    subgraph Middleware 链（顺序固定）
        PSM[ProcessingStepMiddleware<br/>已有：timeline/tool 步]
        FBM[FailureBudgetMiddleware<br/>新增：onActing 统计失败]
        GAM[GoalAlignmentMiddleware<br/>新增：onReasoning 注入对齐提醒]
        TRM[TaskReminderMiddleware<br/>原生：任务清单重注入]
    end
    FBM -->|tool 失败计数| REG[ToolFailureBudgetRegistry<br/>per-run 状态，RuntimeContext 隔离]
    FBM -->|budget 触发| CAT1[Catalog react.tool-failure-budget]
    GAM -->|goal-check 提醒| CAT2[Catalog react.goal-check]
    REG -.->|completeToolStep 参数| PSM
```

| 组件 | 模块 | 职责 |
|------|------|------|
| `GoalAlignmentMiddleware` | orchestrator `agent/` | `onReasoning`：每 N 轮向 `ReasoningInput.messages` 追加瞬态 USER 提醒（原始 query + tasksContext 进度） |
| `FailureBudgetMiddleware` | orchestrator `agent/` | `onActing`：从 `ToolResultEndEvent.state` 判定失败 → 计数 → 达阈值追加瞬态强提示消息 |
| `ToolFailureBudgetRegistry` | orchestrator `agent/` | per-run 失败计数（`toolName` / `toolName+参数指纹` 两个维度）；**无状态 Middleware 的配套状态源** |
| `StepEventBridge` | orchestrator `agent/`（扩展） | 新增 per-call `AgentRunState` 载体：goalCheck 计数器 + failureBudget Map，经 `RuntimeContext` 挂到 bridge 生命周期 |
| `ProcessingStepMiddleware` | orchestrator `agent/`（扩展） | `completeToolStep` 增加 `budgetExceeded` 参数：tool 步 after 换文案 |
| Catalog `react.goal-check` / `react.tool-failure-budget` | prompt-manager DB | 提醒/强提示正文模板（**SSOT，禁止硬编码**） |
| Nacos `agent.execution.react.goal-check.*` / `tool-failure-budget.*` | Nacos | 开关、间隔、阈值（**仅非提示词运行参数**） |

**中间件顺序约束**：`FailureBudgetMiddleware` 必须在 `GoalAlignmentMiddleware` **之前**（同轮 tool 失败 → 当轮 reasoning 即见预算强提示；goal-check 与 budget 提醒可同时出现，budget 在后更贴近模型注意力末端）。

---

## 4. GoalAlignmentMiddleware（L2 进度追踪）

### 4.1 触发条件（全部满足才注入）

1. `agent.execution.react.goal-check.enabled=true`（Nacos，默认 false）
2. role = MAIN（SUB / 专家不注入：无独立任务板、上下文隔离）
3. `tasksContext` 非空（模型已建板；未建板的简单对话不打扰）
4. 当前 reasoning 轮次满足 `iteration % every-n-think == 0`（`every-n-think` 默认 3）
5. 距上次注入后**至少发生过 1 次业务 tool 完成**（连续纯 think 不重复轰炸）

### 4.2 注入内容

复用 `TaskReminderMiddleware` 的瞬态模式：`MsgRole.USER` + `METADATA_SYNTHETIC` + `METADATA_REMINDER_KIND="goal_check"`，追加到 `ReasoningInput.messages` 末尾，**不落 AgentState**。

模板 SSOT = Catalog **`react.goal-check`**，占位符：

| 占位符 | 来源 |
|--------|------|
| `{userQuery}` | `StepEventBridge` 已绑定的 `userQuery`（session 级，runtime 注入 bridge 时可得） |
| `{taskProgress}` | `AgentState.tasksContext` 渲染（`2/5 已完成 · 进行中：功能对比分析`，复用 `TodoTasksBridge` 映射逻辑） |

模板草案（正式文案在 `/prompts` 评审）：

```
<system-reminder>
【目标对齐检查】原始任务：{userQuery}
当前进度：{taskProgress}
在继续行动前请先确认：
1. 已收集的信息是否直接服务于最终输出？剔除与目标无关的探索方向。
2. 当前进行中的子任务完成后，剩余子任务的最短路径是什么？
若发现方向偏离，请用 todo_write 修正任务清单再继续。
</system-reminder>
```

### 4.3 与其他提醒的共存

- 与原生 `TaskReminderMiddleware` 的 `todo_state` 提醒**互补不重复**：原生每轮列清单（是什么），本 Hook 周期性问目标（为什么）。
- 同轮叠加时顺序：`todo_state`（原生）→ `goal_check`（本 Hook）→ `tool_failure_budget`（若有）。

---

## 5. FailureBudgetMiddleware（L3 动态重规划）

### 5.1 失败判定（复用 4.5.7 契约）

`onActing` 返回 Flux 内拦截 `ToolResultEndEvent`：

| state | 判定 |
|-------|------|
| `ERROR` | 失败，计数 |
| `INTERRUPTED` | 用户取消，**单独计数维度**（对齐 4.5.7 同族预算语义），不混入 ERROR |
| `DENIED` / `RUNNING` / `SUCCESS` | 不计数 |

**排除**：`todo_write`（状态工具）、`spawn_subagent`（元工具）、HITL 暂停中的工具。

### 5.2 预算维度与阈值

`ToolFailureBudgetRegistry` per-run 维护两个 Map（key 见下，value=连续失败次数；**成功即清零该 key**）：

| 维度 | key | 阈值（Nacos） | 语义 |
|------|-----|--------------|------|
| 同参数死循环 | `toolName + sha1(规范化 input JSON)` | `same-signature-max`（默认 2） | 防「相同参数连调」——overlay 已要求但模型不遵守 |
| 同工具连续失败 | `toolName` | `per-tool-max`（默认 3） | 工具/数据源整体不可用，强制换方案 |

参数指纹规范化：key 排序后的 JSON 序列化；**不**对 value 做语义归一（避免误合并）。

### 5.3 触发动作（一次性，防轰炸）

任一维度达阈值时，向**下一轮** `ReasoningInput.messages` 追加瞬态强提示（`METADATA_REMINDER_KIND="tool_failure_budget"`），每 run 每 key 只触发一次。模板 SSOT = Catalog **`react.tool-failure-budget`**，占位符 `{toolName}` / `{failCount}` / `{lastError}`（`lastError` 取 tool 结果首行截断 200 字，仅供模型参考，非展示）：

```
<system-reminder>
【执行受阻】工具 {toolName} 已连续失败 {failCount} 次（最近错误：{lastError}）。
禁止再用相同思路重试。你必须立即三选一：
1. 换用其他工具或备选数据源获取等价信息；
2. 跳过该子任务，先推进不受影响的任务（用 todo_write 调整状态）；
3. 若该数据为关键路径且确实无法获取，向用户如实说明现状与影响，然后基于已有信息收束作答。
</system-reminder>
```

### 5.4 Timeline 投影

预算触发的**那次 tool 步** after 文案换为「连续失败，需调整方案」（Nacos `agent.timeline.steps.tool-failure-budget.after`）：

- `ProcessingStepMiddleware.completeToolStep` 签名增加 `boolean budgetExceeded`；为 true 时忽略 catalog `timelineSummary`，直接用上述文案完成 tool 步。
- **不新增步骤类型/phase**；goal-check 注入**不上** Timeline（对齐原生 reminder：对模型可见、对用户透明，避免时间线噪音）。

### 5.5 与既有机制的边界

| 机制 | 关系 |
|------|------|
| 4.5.7 同族取消预算（3 次硬拒） | **正交**：那是「用户取消后」的执行层硬拒；本设计是「工具自发失败」的引导层软干预。INTERRUPTED 不计入本预算 |
| `max-iters` | 不改动；预算强提示通常让模型远早于 max-iters 收束 |
| plan-workflow `NodeRetryExecutor` | 不动；本设计仅 ReAct |
| Grounding 终态校验 | 互补：本设计在**过程中**纠偏，Grounding 在**终态**拦截 |

---

## 6. 状态管理与无状态约束

对齐 P2-1（E5）「Middleware 无状态」铁律：

- `GoalAlignmentMiddleware` / `FailureBudgetMiddleware` 实例**不持有** per-run 字段；两个 Middleware 均注册为共享单例（`ProcessingStepMiddlewareFactory.shared()` 同模式，或合入该工厂）。
- per-run 状态载体 = `AgentRunState`（新建，挂 `StepEventBridge` 与 bridge 同生命周期）：
  - `goalCheckLastInjectedIter` / `goalCheckToolDoneAtLastInject`
  - `failureCounts: Map<String,Integer>` / `budgetTriggeredKeys: Set<String>`
- Middleware 经 `bridgeIdOf(ctx)` → `StepEventBridge.runState(bridgeId)` 读写；`StepEventBridge.clear(bridgeId)` 时随 bridge 一并回收（**天然防泄漏**，续跑 resume 重建 bridge 即重置预算——可接受：续跑是新 run）。

---

## 7. 配置

### 7.1 Nacos（`docs/nacos/sunshine-orchestrator.yaml` → `AgentExecutionProperties.React` 扩展）

```yaml
agent:
  execution:
    react:
      goal-check:                # L2 目标对齐
        enabled: false           # 灰度开关，先 false 观察
        every-n-think: 3
      tool-failure-budget:       # L3 失败预算
        enabled: false
        same-signature-max: 2
        per-tool-max: 3
```

### 7.2 Catalog（prompt-manager DB，`/prompts` 维护）

| id | 用途 |
|----|------|
| `react.goal-check` | goal-check 提醒模板（§4.2 草案起步） |
| `react.tool-failure-budget` | 预算强提示模板（§5.3 草案起步） |

### 7.3 时间线文案（Nacos `agent.timeline.steps`）

```yaml
agent:
  timeline:
    steps:
      tool-failure-budget:
        after: 连续失败，需调整方案
```

---

## 8. 子任务拆分

| 编号 | 内容 | 产出 |
|------|------|------|
| 4.7.7a | `AgentRunState` + `StepEventBridge` 扩展 + `AgentExecutionProperties` 配置项 | orchestrator + 单测 |
| 4.7.7b | `ToolFailureBudgetRegistry` + `FailureBudgetMiddleware` + `completeToolStep` budget 文案 | orchestrator + 单测（含 state 判定矩阵、指纹规范化、成功清零、一次性触发） |
| 4.7.7c | `GoalAlignmentMiddleware` + 触发条件（间隔/工具闸门/MAIN-only） | orchestrator + 单测 |
| 4.7.7d | Catalog 两条模板 + Nacos 配置 + timeline 文案 + `sync_nacos.py` | 配置 |
| 4.7.7e | Live 验收脚本 `verify_goal_alignment_live.py` | scripts + 验收记录 |

**建议顺序**：a → b → c → d → e（b 可独立先行验证价值）。

---

## 9. 验收

### 9.1 单测

```bash
mvn test -pl orchestrator -Dtest=FailureBudgetMiddlewareTest,GoalAlignmentMiddlewareTest,ToolFailureBudgetRegistryTest
```

| 用例 | 预期 |
|------|------|
| 同 tool 同参数连续 ERROR × 2 | 第 2 次后下一轮 ReasoningInput 含 budget 提醒（SYNTHETIC 标记） |
| 同 tool 成功 1 次 | 该 signature 与 toolName 计数均清零 |
| INTERRUPTED | 不计入 ERROR 预算 |
| budget 每 run 每 key | 只触发一次 |
| goal-check：无 tasksContext | 不注入 |
| goal-check：iter 3 且期间有 tool 完成 | 注入一次，iter 4-5 不重复，iter 6 再注入 |
| goal-check：SUB role | 不注入 |
| budget 触发的 tool 步 | after = 「连续失败，需调整方案」 |

### 9.2 Live（`verify_goal_alignment_live.py`）

| # | 场景 | 预期 |
|---|------|------|
| G1 | 多步调研句（竞品调研类），taskboard + goal-check 开启 | think 流中每 3 轮出现目标对齐行为（正文/todo 调整可见）；无偏离收束 |
| G2 | 构造工具持续失败（停用某工具或错误参数场景） | 达阈值后模型换工具/跳过/收束三选一，不再同参数重试；Timeline 该 tool 步 after 为「连续失败，需调整方案」 |
| G3 | `enabled=false` | 行为与现状完全一致（回归门禁） |
| G4 | 简单单轮「你好」 | 无任何注入、无 tasks 步（F-N3 回归） |

---

## 10. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 提醒注入过多干扰模型 / 膨胀 context | 瞬态注入不落盘、不参与 compaction；间隔 + 工具闸门 + 一次性触发三重节流；灰度开关默认 false |
| goal-check 提醒被模型当成新用户指令 | `<system-reminder>` 包裹 + `METADATA_SYNTHETIC`；模板措辞明确「检查后继续当前任务」 |
| 参数指纹误合并（不同语义同 JSON） | 仅做 key 排序规范化，不做 value 语义归一；误合并的最坏结果是早一轮提醒，可接受 |
| 模型无视强提示继续死循环 | 兜底仍是 `max-iters`；不在本设计做执行层硬拒（保持「引擎不替模型决策」原则，硬拒仅保留给用户取消路径 4.5.7） |
| 续跑（checkpoint resume）预算重置 | 新 run 新 bridge 即重置；失败历史已在模型可见的上下文中，影响有限 |

---

## 11. 明确不做

- 显式 Replan 节点 / DAG 化 TaskBoard（D11 锁定边界）
- LLM 二次校验调用（每轮额外模型调用判偏离）
- 执行层硬拒同参数工具调用（仅软提示；硬拒只属 4.5.7 用户取消路径）
- goal-check / budget 提醒落 `AgentState.context` 或参与 compaction
- 前端新增组件 / `STEP_ORDER` 变更
- SUB Agent / peer-collab 专家侧注入（MAIN-only）

---

## 12. 可选后续（自归档 4.7.8 吸收 · 非本卡主路径）

> 原 [harness-loop-enhancement](./archive/2026-07-28-harness-loop-enhancement-design.md) 评审结论：阶段四/五相对现状为过时或负优化（run 内 compaction 以五层 §4.5 方案 A 为准；`max-iters` 已高于原提案）。下列两项**仅在 Live 有实锤痛点时**再开子卡，**默认不做**。

| 可选 | 动机 | 约束 | 依赖 |
|------|------|------|------|
| **CompletionGuard（写后必验证）** | task 编码：有写工具未见验证工具即收束时，瞬态注入续跑提示（与 Grounding「内容校验」互补） | MAIN-only；`max-guard-per-run` 防死循环；纯调研不触发；**软提示非硬拒**；优先先靠 Catalog，再考虑 Middleware | 本卡 `AgentRunState`（a）落地后 |
| **React 瞬态工具重试** | TIMEOUT / 限流等不占 LLM 轮次自动重试 1 次（复用 `ExecutionErrorClassifier`） | 与本卡 `FailureBudget` 叠层时：重试成功不计预算；**禁止**默认重试 >1；观察痛点再开 | 本卡 FailureBudget（b）后评估 |

**不迁入本卡**：子 Agent 回传经济学 → [multi-agent §4.4.1](./2026-07-29-multi-agent-unified-design.md)；run 内 / 跨轮压缩 → [五层压缩](./2026-07-31-unified-context-compression-design.md)。
