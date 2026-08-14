# 统一资源路由设计（用户显式三模式 + 双轨意图收集）

> **状态**：✅ **R-0～R-4 全部完成**（R-0～R-3：[unified-routing-v6-h5](../plans/2026-08-13-unified-routing-v6-h5.md) + rebuild **H-5**；**R-4 = rebuild 阶段 D**：源码零残留）· **v6（2026-08-10）重写** · **v7（2026-08-14）R-4 核对完成**  
> **日期**：2026-07-29（初稿）· 2026-07-30（v2/v3）· 2026-08-05（v5 harness 对齐）· **2026-08-10（v6：三模式显式选择，取消自动模式识别）** · **2026-08-13（R-0～R-3 + H-5 落地；R-4 另开）** · **2026-08-14（R-4 核对完成；`ForcedExecutionRouter` 重写语义保留）**  

> **编号**：阶段四增量（路由层重构）  
> **前置**：[multi-agent-unified](./2026-07-29-multi-agent-unified-design.md) · [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md)（**内核 H-0～H-4 ✅**；D1 删除动态 Plan-Workflow = 阶段 D **✅**；S5 v4 单一循环）· [workflow-structured-io](./archive/2026-07-24-workflow-structured-io-design.md)  
> **一句话**：用户在前端显式选择 **快速 / 专业 / 工作流** 三种执行模式（**取消** L3 自动模式识别与 `auto`）。快速→ReAct（可 spawn 子 Agent）；专业→Planner-Executor；工作流→静态 Workflow。意图链按模式分轨：快/专收集 skill+子 Agent；工作流只收集 workflow。`#` 补全**仅工作流模式**显示。

### v6 相对旧稿的废止项

| 旧设计 | v6 |
|--------|-----|
| L3 输出 `planMode=none\|harness`（自动判执行路径） | **删除**；执行路径 = 用户所选模式 |
| `ExecutionPreference=auto` / `plan-workflow` | **删除**；改为 `fast` / `pro` / `workflow` |
| 「Plan-Workflow 保留代码仅去路由」 | 对齐 rebuild **D1：整套删除** |
| Pre-Routing 含 Plan Approval | 对齐 **D5**：仅 HITL / 续跑等等待态 |
| `call_scene=plan-phase` / 两态·INCREMENTAL | 对齐 rebuild **S5 v4**：无分解模式；调用点 `callSite=plan` / `worker` / `self-assess` |
| ResourceDispatcher 保留 `PlanWorkflowExecutor` | **删除**该分支 |
| 前端删除执行模式选择器 | **保留**三模式选择器（改选项）；`#` 仅 workflow 模式 |

---

## 0. 术语

| 术语 | 含义 |
|------|------|
| **执行模式（ExecutionMode）** | 用户显式选择：`fast`（快速）/ `pro`（专业）/ `workflow`（工作流）。**路由不改写** |
| **意图收集（Intent Gathering）** | L0–L3：按模式分轨收集资源候选（skill/agent **或** workflow），**不**决定执行模式 |
| **资源（Resource）** | workflow / agent / skill |
| **主 Agent** | 通用 ReAct（快速）或 Planner（专业）；路由命中的 agent **一律子 Agent**，经 `spawn_subagent` 委派 |
| **Pre-Routing** | HITL / 续跑等系统等待态复用上次路由结果；**不含** Plan Approval（D5） |
| **kind**（会话形态） | `chat` / `task`（产品选择；记忆闸门 / 工作区 / **默认工具集** / 资源可发现过滤；**正交于**执行模式）。**弃用**字段名 `scene` 承载本语义 |
| **callSite**（LLM 调用点） | `plan` / `worker` / `self-assess` / `rewrite`…（orchestrator 注入，供模型路由/用量）。**弃用** `call_scene` |
| **biz_scene** | 业务域编码（Policy/任务板；随 Skill/Agent 元数据；码表 = 业务场景 Lab）。**禁止**写入 `kind` 或 `callSite`；**禁止**再用 `reactPromptId` 充当业务域 |

### 命名四轴（2026-08-13 · 去 scene overload）

| 轴 | 协议字段 | 旧名（废弃） | 取值 | 定责 |
|----|----------|--------------|------|------|
| 会话形态 | `kind` | `scene`（chat/task） | 会话：`chat` \| `task`；Catalog 资源另含 `all` | 用户 / 会话；资源元数据同轴 |
| 执行模式 | `executionMode` | — | `fast` \| `pro` \| `workflow` | 用户 |
| 业务域 | `biz_scene` | —（可二期 `bizDomain`） | 闭集码（Lab） | Catalog Skill/Agent 元数据 |
| LLM 调用点 | `callSite`（JSON/DB：`call_site`） | `call_scene` | `plan` \| `worker` \| … | orchestrator 注入 |

**禁止**四轴同名字段互写。L3 向量元数据里旧列 `scene=chat|task` → 迁为 `kind`（或过渡双读）。

**资源 `kind` 过滤与默认工具集**（2026-08-13）：意图候选构建前，Skill/Agent/Workflow 仅保留 `kind ∈ {会话.kind, all}`；默认 Toolkit 按会话 `kind` 解析 `chat`/`task` 工具集，**不**按 `executionMode` 选集。细则与 React Prompt 退役见 [kind-biz-scene-catalog](./2026-08-13-kind-biz-scene-catalog-design.md)。

---

## 1. 目标与非目标

### 1.1 目标

1. **三模式显式**：快速 / 专业 / 工作流由用户选择，后端只校验与分发，**不做**自动模式识别。  
2. **双轨意图**：快/专轨收集 skill + 子 Agent；工作流轨只收集 workflow。  
3. **前端约束**：仅工作流模式展示 `#` 工作流补全；快/专展示 `$` / `@`（若产品需要）。  
4. **对齐 4.14**：专业模式 = Planner-Executor（单一循环）；动态 Plan-Workflow **删除**（D1）。  
5. agent 命中一律子 Agent；主编排者随模式为 ReAct 或 Planner。

### 1.2 非目标

- 不恢复 `auto` / `plan-workflow` / `PEER_COLLAB` 顶层模式。  
- 不在 L3 输出「该走快速还是专业」。  
- 不做 Plan Approval。  
- 不把 TaskBoard 做成 mini-DAG（D11）。

---

## 2. 执行模式（用户 SSOT）

| 模式 | 协议值 | 执行器 | 能力边界 |
|------|--------|--------|----------|
| **快速** | `fast` | `ReactExecutor` | ReAct 循环；可 `spawn_subagent`；TaskBoard 软规划可选 |
| **专业** | `pro` | `PlannerHarnessExecutor` | Planner-Executor 单一循环（rebuild S5 v4）；Worker + 可选内部 spawn |
| **工作流** | `workflow` | `WorkflowExecutor` | **仅** 4.13 静态 Workflow（Studio / `#id` / 规则·召回选定） |

映射（迁移）：

| 旧 `ExecutionPreference` | 新 |
|--------------------------|-----|
| `auto` | **删除**（默认建议 `fast`） |
| `react` | `fast` |
| `plan-workflow` | `pro`（语义变为 Planner-Executor，非动态 DAG） |
| `workflow` | `workflow` |

请求体字段建议：`executionMode: fast|pro|workflow`（可过渡期兼容旧名，落地后删 `auto`/`plan-workflow`）。  
`ForcedExecutionRouter` **取消**——模式已由用户钉死，不再「强制覆盖意图分类结果」；路由只做资源收集。

---

## 3. 总览架构

```
用户选择 executionMode + kind + userMessage
        │
        ├── Pre-Routing（HITL / 续跑…）→ 复用上次 RoutingResult，不进收集链
        │
        └── IntentGatheringChain（按 mode 分轨，L0→L1→L2→L3）
                │
                ├── mode ∈ {fast, pro}  → 轨 A：收集 agentIds[] + skillIds[]
                │                         （忽略 / 禁用 #workflow）
                │
                └── mode = workflow     → 轨 B：收集 workflowId
                                          （忽略 $ / @；仅 workflow 候选）
        │
        └── ResourceDispatcher（只读 executionMode，不改写）
                ├── fast      → ReactExecutor(agentIds, skillIds, kind)
                ├── pro       → PlannerHarnessExecutor(agentIds, skillIds, kind)
                └── workflow  → WorkflowExecutor(workflowId)
```

**关键**：L3 **不再**输出 `planMode`。`RoutingResult.executionMode` = 请求中的用户选择（原样回传）。

---

## 4. 数据模型

```java
public record RoutingResult(
    ExecutionMode executionMode, // fast | pro | workflow — 用户选择，路由不改写
    String kind,                 // chat | task（会话形态；旧字段名 scene 废弃）
    String workflowId,           // 轨 B；轨 A 为 null
    List<String> agentIds,       // 轨 A；轨 B 为空
    List<String> skillIds,       // 轨 A：**已触发**（全文 overlay）；非可发现全集；轨 B 为空
    Map<String, Object> params,
    String reason
) {
    public enum ExecutionMode { FAST, PRO, WORKFLOW }
}
```

```java
public record RoutingContext(
    String userId,
    String tenantId,
    String conversationId,
    String userMessage,
    String kind,                  // chat | task
    ExecutionMode executionMode,  // 必填，来自请求
    List<ChatTurn> recentHistory  // L3 深层兜底用
) {
    public boolean isAgentSkillTrack() {
        return executionMode == ExecutionMode.FAST || executionMode == ExecutionMode.PRO;
    }
    public boolean isWorkflowTrack() {
        return executionMode == ExecutionMode.WORKFLOW;
    }
}
```

> **相对 v3 删除**：`planMode`、`ResourceType` 驱动执行分叉、`GUIDED` / `confidence` 门控改模式。置信度若保留，**只**用于候选采纳（要不要把某 agent/skill/workflow 放进结果），**永不**改 `executionMode`。

---

## 5. 轨 A：快速 / 专业（收集 skill + 子 Agent）

目标：为通用 ReAct 或 Planner 准备**可委派的子 Agent 列表**与 **skill 物料**；执行器由用户模式决定。

### 5.1 各层职责

| 层 | 行为 |
|----|------|
| **L0** | 解析 `$agent-id(s)`、`@skill-id(s)` → 累积；**不解析 `#`**（前端本模式不展示；后端若出现则忽略或 400，推荐忽略并打 warn） |
| **L1** | 仅匹配 `resourceType ∈ {agent, skill}` 的规则 → 累积；**跳过** workflow 规则 |
| **L2** | 仅 **Agent / Skill** 两路 embedding 召回 → 累积候选；**不跑** Workflow 索引 |
| **L3** | 快速分类或深层兜底：在候选 / Catalog 中筛选、合并 `agentIds` / `skillIds`；输出 **不含** 执行模式字段 |

L0–L2 **不 STOP 整链提前返回执行器**（无 workflow 独占）；始终跑完收集（可用短路径跳过空 L3 输入优化，但结果仍是「资源包」而非模式）。

### 5.2 L3 输出契约（轨 A）

```json
{
  "agentIds": ["contract-review", "finance-analyst"],
  "skillIds": ["5b-skill-doc"],
  "confidence": 0.86,
  "reason": "..."
}
```

- 二者皆空 → 合法：纯通用 ReAct / Planner（无预置子 Agent / skill）。  
- **禁止**输出 `executionMode` / `planMode` / `workflowId`。

### 5.3 分发

| 用户模式 | 执行 |
|----------|------|
| `fast` | `ReactExecutor`：主 Agent = 通用 ReAct；注入 Agent Catalog 摘要（不把业务 agent prompt 当主系统提示）；挂载 skills；运行中可 `spawn_subagent(agent_id=…)` |
| `pro` | `PlannerHarnessExecutor`：Planner = ReAct 主 Agent + H1；同样可把 agentIds/skillIds 注入为可调度能力（Worker `toolWhitelist` / spawn 池）；**单一循环**（rebuild §3） |

---

## 6. 轨 B：工作流（只收集 workflow）

目标：选定**一个**静态 workflow 定义并执行。

### 6.1 各层职责

| 层 | 行为 |
|----|------|
| **L0** | `#workflow-id` → 命中即锁定 `workflowId`（可短路径结束收集） |
| **L1** | 仅 workflow 规则 → 命中可锁定 |
| **L2** | 仅 **Workflow** embedding 召回 → 候选列表 |
| **L3** | 在候选 / 全量 Workflow Catalog（+ 必要时 L1 会话快照）中选定 `workflowId`；**不**产出 agent/skill |

`$` / `@`：前端本模式不展示；后端忽略。

### 6.2 未命中

- 无 `#`、规则未中、召回/L3 仍空 → **明确错误/引导**（请 `#` 选择或配置规则），**不**静默降级到 ReAct（用户已选工作流模式，降级会违背显式选择）。  
- 产品可提供「改选快速模式」的前端提示，而非后端偷改 `executionMode`。

### 6.3 分发

`WorkflowExecutor` + `StaticPlanAdapter`；DAG 画布仅此模式（rebuild D2/D3）。

---

## 7. ResourceDispatcher

```java
@Component
@RequiredArgsConstructor
public class ResourceDispatcher {
    private final ReactExecutor reactExecutor;
    private final PlannerHarnessExecutor plannerHarnessExecutor;
    private final WorkflowExecutor workflowExecutor;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        RoutingResult r = ctx.routingResult();
        return switch (r.executionMode()) {
            case FAST -> reactExecutor.execute(ctx);           // agentIds/skillIds 来自轨 A
            case PRO -> plannerHarnessExecutor.execute(ctx);   // 同上
            case WORKFLOW -> workflowExecutor.execute(ctx);    // workflowId 来自轨 B
        };
    }
}
```

- **无** `PlanWorkflowExecutor` 分支（D1）。  
- **无** 根据 L3 `planMode` 二次分流。

---

## 8. 前端约定

| 项 | 行为 |
|----|------|
| 模式选择器 | 三项：**快速** / **专业** / **工作流**（删除「自动」「动态规划」） |
| `#` 工作流补全 | **仅** `executionMode=workflow` 时显示与解析 |
| `$` / `@` | 仅 `fast` / `pro` 显示（与轨 A 一致） |
| 默认值 | 建议 `fast`（会话级可记忆） |
| kind | 会话形态 chat/task（工作区）；与执行模式正交；**勿再叫 scene** |

底栏示意：

```
[ 快速 ▾ ]  [输入框…  $ @ ]          ← fast / pro
[ 工作流 ▾ ] [输入框…  # ]           ← workflow
```

---

## 9. Pre-Routing

| 等待态 | 处理 |
|--------|------|
| HITL 工具确认 | 复用上次 `RoutingResult`，不重跑收集链 |
| 续跑 / reconnect | 同上 |
| ~~Plan Approval~~ | **不做**（D5） |

---

## 10. 与 ReactExecutor / Planner 的装配（轨 A）

1. 加载通用系统提示（`mode-overlay.react` 或 `planner.harness`，按模式）。  
2. `agentIds` 非空 → 注入 Agent Catalog 摘要（供 spawn），**不**把业务 agent 提成主系统提示。  
3. **Skill 可发现**：租户/场景启用 Catalog → 注入 **名+description** 目录（L2 至多提权排序）；**禁止**召回命中即灌全文。  
4. **Skill 触发**：`skillIds`（本字段 = **已触发**）→ 全文 overlay；触发时懒挂沙箱。L0 `/`、上轮 sticky、极少 force-trigger；（可选）L3 高置信。  
5. `AgentRuntime.run(MAIN|PLANNER)`；spawn → `AgentRunRequest.sub` / Worker 内 spawn。

安全模型不变：HITL / SandboxExecGuard / PathJail / 租户 / Catalog 启用池。

> **Skill 可发现≠触发**（[skill-sticky v3.1](./2026-08-12-skill-sticky-process-chain-design.md)）：`skillIds` 只表示 triggered；消息完整 `RoutingResult` + 触发集轻 sticky。租户「固定」= 固定可发现，不是固定 overlay。不做 Redis ledger / 软链 / `processGraph`。

---

## 11. callSite（原 call_scene；命名隔离，对齐 rebuild S5 v4）

| 字段 | 语义 |
|------|------|
| `kind` | 会话形态 chat/task（旧 `scene`） |
| `callSite` / `call_site` | LLM 调用点：`plan` / `worker` / `self-assess` / `rewrite`…（旧 `call_scene`） |
| `biz_scene` | 业务域；与上两者无关 |
| `executionMode` | 执行模式；与上三者正交 |

**删除** 旧枚举值 `plan-phase`、`evaluator`（不论字段叫 call_scene 还是 callSite）。强弱模型分层走 phase5 5.3，**不**绑路由模式枚举。

迁移：API/Java 用 `callSite`；DB/MQ 用 `call_site`；过渡期可读旧键 `call_scene`，落地后删除。

---

## 12. 组件处置

### 12.1 删除 / 废止

| 项 | 说明 |
|----|------|
| `auto` / `plan-workflow` 偏好 | 迁移见 §2 |
| L3 `planMode` 输出与判定文案 | 取消自动模式识别 |
| `PlanWorkflowExecutor` 及动态 DAG 规划链路 | rebuild D1（**已删**） |
| `PlanApproval*` | D5（**已删**） |
| `PEER_COLLAB` 顶层模式 | spawn 中心化（已有） |

### 12.2 修改

| 项 | 说明 |
|----|------|
| `ExecutionPreference` → `ExecutionMode` | `fast` / `pro` / `workflow` |
| `ExecutionModeSelector` | 三选项；mention 开关按 §8 |
| `ForcedExecutionRouter` | **重写语义保留**（非删除）：v6 主路径的分轨资源收集器——钉死 `executionMode` 分轨 A（skill/agent）/B（workflow），永不改 mode；类名沿用，职责取代旧「强制覆盖意图分类」 |
| `IntentRouter` / Policy Chain | 按 `executionMode` 分轨 A/B |
| L2 索引调用 | 轨 A：agent+skill；轨 B：workflow |
| `ChatController` | 必填 `executionMode`；透传 `kind`（弃用请求体 `scene`） |
| `ExecutionDispatcher` → `ResourceDispatcher` | §7 |

### 12.3 新建

| 项 | 说明 |
|----|------|
| `RoutingResult`（v6 字段） | §4 |
| `RoutingAccumulator` | 分轨累积 |
| `RoutingPolicyChain` | 按 mode 选择策略集 |
| embedding 索引 | Agent / Skill / Workflow（按轨选用） |

---

## 13. 实施阶段（建议）

| 阶段 | 状态 | 内容 | 出口 | 融合 plan |
|------|:----:|------|------|----------|
| **R-0** | ✅ | 协议：`executionMode` 三值 + 前端选择器 + mention 开关；删 auto/plan-workflow 文案 | 前后端契约单测 | T1–T2 |
| **R-1** | ✅ | `RoutingResult` + 分轨 Chain（L0/L1）+ `ResourceDispatcher` 三分支 | 编译绿；模式钉死分发 | T3–T4 |
| **R-2** | ✅ | 轨 A/B 的 L2/L3（无 planMode） | 单测：同 query 不同 mode → 不同候选域 | T5 |
| **R-3** | ✅ | 专业模式接到 `PlannerHarnessExecutor`（= rebuild H-5）；三模式冒烟 | Live：`verify_routing_v6_smoke.py` V1/V3/V4/V5 | T3+T7 |
| **R-4** | ✅ | 删除动态 plan-workflow 残留（`WorkflowPlanner`/`PlanWorkflowExecutor`/`PlanApproval*`）、`PLAN_WORKFLOW` 旧枚举；`ForcedExecutionRouter` 重写保留（见 §12.2） | grep 零残留 ✅ | rebuild **阶段 D** |

> 与 4.14：R-3 = H-5 接线 **✅**（[unified-routing-v6-h5](../plans/2026-08-13-unified-routing-v6-h5.md)）。**延期**：`intent.classifier` Catalog live 版本 bump；H-7 Live（代码 ✅，待部署跑）；R-4 = rebuild 阶段 D **✅**（源码删除完成，Live 回归随 H-7 部署后跑）。`pro` **禁止**静默改回 `fast`。

---

## 14. 验收标准

| # | 场景 | 预期 |
|---|------|------|
| V1 | 用户选快速，无 `$/@` | → ReactExecutor；可纯 ReAct |
| V2 | 用户选快速，`$agent-A` | → ReactExecutor；Catalog 含 A；可 spawn |
| V3 | 用户选专业，同 query | → PlannerHarnessExecutor；**不**走 React 主路径 |
| V4 | 用户选工作流，`#wf-1` | → WorkflowExecutor(wf-1)；无 agent/skill 收集 |
| V5 | 用户选工作流，无候选 | → 明确失败/引导，**不**改成快速 |
| V6 | 快速/专业模式输入框 | **无** `#` 补全 UI |
| V7 | 工作流模式输入框 | **有** `#`；无 `$/@`（或禁用） |
| V8 | L3 响应 | **无** `planMode` / 改写 `executionMode` |
| V9 | 静态 Workflow 回归 | DAG 画布仅工作流模式 |
| V10 | 动态 Plan-Workflow | 路由与执行器均不可达（D1） |

---

## 15. 关联文档

| 文档 | 关系 |
|------|------|
| [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md) | 专业模式执行体；D1/D5/S5 v4；本文 §7/§11 对齐 |
| [multi-agent-unified](./2026-07-29-multi-agent-unified-design.md) | 子 Agent / spawn |
| [phase5](./phase5-operation-openness-design.md) | `callSite`（原 call_scene）模型路由；与 `kind` 隔离 |
| [prompt-ops-routing-catalog](./archive/2026-07-20-prompt-ops-routing-catalog-design.md) | 规则 `resourceType`；轨 A/B 过滤 |
| [skill-sticky-process-chain](./2026-08-12-skill-sticky-process-chain-design.md) | 轨 A：可发现≠触发；`skillIds`=triggered；S-0 保真 + 轻 sticky（v3.1） |

---

## 16. 风险与对策

| 风险 | 对策 |
|------|------|
| 用户误以为仍会「自动选模式」 | 文案去掉「自动」；默认 `fast` 并说明 |
| 工作流未命中体验差 | 前端强引导 `#`；错误码稳定，禁止静默降级 |
| 专业模式未就绪 | 灰度开关；失败明示，不改 mode |
| 旧会话仍存 `auto`/`plan-workflow` | 读时映射：`auto→fast`，`plan-workflow→pro`，写回新枚举 |
| 轨 A 候选过多 | L2 Top-K + L3 合并；Catalog 描述治理 |

---

## 17. 旧版章节说明

v1–v5 中「L3 判定 planMode」「Plan-Workflow 直连 API」「删除执行模式选择器」「两态/INCREMENTAL 路由影响」等正文**以本文 v6 为准全部覆盖**。实施与评审只读本文；若需历史细节见 git 历史。
