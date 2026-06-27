# 路由 Golden-Set（验收提示词）

> **SSOT**：人工/UI 验收 + `RoutingGoldenSetTest` 单测对照  
> **配置**：`docs/nacos/sunshine-orchestrator.yaml` → `agent.routing.structural` / `agent.routing.rules` / `agent.execution.plan-workflow`  
> **代码**：`ExecutionPlanRouter` → `RoutingPolicyChain`（L0→L3）  
> **重试/降级详设**：[plan-workflow-retry-degradation.md](./plan-workflow-retry-degradation.md)

## 策略链（意图识别步内）

| 层级 | Policy | 配置 | 产出 |
|:----:|--------|------|------|
| L0 | `SkillBindingRoutingPolicy` + `SkillDiscoveryService` | `agent.skill.hint-patterns` + `@` 硬编码 | 单步：`REACT`+skillId；多步 `@`/强提示：`PLAN_WORKFLOW` **5B**；L3 后自动发现 skill |
| L1 | `StructuralRoutingPolicy` | `agent.routing.structural` | `PLAN_WORKFLOW` |
| L2 | `GoldenRuleRoutingPolicy` | `agent.routing.rules` | 静态 `WORKFLOW` |
| L3 | `LlmClassifierRoutingPolicy` | `agent.intent.classifier-prompt` | LLM 选 mode/workflow |
| L3+ | （阶段四）第五 mode `peer-collab` | 同上 + `agent.routing.peer.*` | 见 [§E](#e-peer_collab阶段四) |
| **强制** | `ForcedExecutionRouter` | 请求体 `executionPreference` ≠ `auto` | 覆盖 L1–L3；见 [§J](#j-chat-executionpreference-强制路由p0) |

**链规则**：首个返回 `ExecutionPlan` 的策略胜出；L1 命中后 L2/L3 不执行。L2 内仍调用 `StructuralPlanMatcher` 作 L1 漏判保险丝。**`executionPreference` 非 auto 时**直接走 `ForcedExecutionRouter`，不进入 Policy Chain。

**时间线**：上述全部发生在 SSE **`intent`（识别意图）** 步；完成后才进入 `plan` / `node-*` / ReAct 步骤。

## 单测

```bash
mvn test -pl orchestrator -Dtest=StructuralPlanMatcherTest,RoutingGoldenSetTest,ExecutionPlanRouterTest,RuleBasedRouterTest,SkillDiscoveryServiceTest,SkillBindingParserTest
```

## 验收前准备

1. **新建对话**（或 `python scripts/clear_session_cache.py --force`）
2. Nacos 已同步：`python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml`
3. orchestrator :8200 已重启

---

## A. PLAN_WORKFLOW（L1 结构守卫）

**预期 intent after**：「…将动态规划多步执行」

### Plan 可视化（成功路径 — 含静态 WORKFLOW）

意图步之后应出现 **「执行计划」** + **Plan DAG**（`PlanWorkflowPanel`），而非 ReAct 的「规划推理」或逐步 `node-*` 卡片：

- **动态 Plan（L1/L3）**：Planner 产出 JSON → `PlanWorkflowExecutor`
- **静态 Workflow（L2）**：Nacos 定义经 `StaticPlanAdapter` 物化为 Plan → `WorkflowExecutor`；plan 步 `detail` 含 **`planId=`**（与动态 Plan 同门控）
- **不应**在成功路径出现：`规划推理` / `think` / 自主 ReAct 工具链（除非 Planner 失败降级，见下）
- **`think` 仅属 ReAct**：answer 的 reasoning 在 `node-*` 步骤与 Plan 抽屉「综合分析」，**不得**再合成顶层 `think` 行

```
识别意图 → 将按「财务待办查询」流程处理   （静态）或 将动态规划多步执行（Plan）
执行计划 → 检索知识库 → 查询待审批… → …（节点链摘要）  [查看详情 / DAG]
```

- **「查看详情」**：跳转 `/plans/:planId`，展示 validated Plan JSON、节点 trace、状态
- 静态 workflow 验收见 [§B–D](#b-静态-workflow--finance-listl2-黄金规则)；动态 Plan 验收见下表 A 类

### Plan 节点抽屉（answer / 汇总节点）

点击 DAG 节点打开 `PlanNodeDrawer`：

| 区块 | 数据源 | 说明 |
|------|--------|------|
| 综合分析 | `step.reasoning` | 模型 reasoning 通道，原样 Markdown |
| 最终输出 | `step.result` | 模型 content 通道（消息正文同步） |
| 执行记录 | `execution_trace.attempts[]` | 节点重试时展示 attemptNo / errorClass / summary |
| （无）执行摘要 | — | answer/llm 不展示时间线 after |

长文由抽屉 `.drawer-body` 统一滚动；**禁止**区块内嵌套滚动条。answer prompt 不对 → 改 Nacos `agent.prompt.answer-template` 或 `mode-overlays.workflow`。

### 降级路径（Planner/校验失败 → Replan → ReAct）

规划期：**Planner 调用重试** → **Replan**（带校验错误反馈，默认最多 2 次）→ 仍失败则 **降级 ReAct**（`status=rejected`）。

| 现象 | 含义 |
|------|------|
| plan 步 `detail` 含 `replanCount` | 曾触发 Replan |
| **执行计划** after「规划经 N 次修正后开始执行」 | Replan 后校验通过 |
| **执行计划** 一行摘要「Plan 校验未通过，降级为 ReAct」、**无 DAG 图** | 校验耗尽，改 ReAct 执行 |
| 有 `执行计划` 摘要含「Planner 未产出…改由自主智能体」 | Planner 调用/解析失败 |
| 有 `执行计划` + DAG + 后续 `规划推理` | 旧版行为；升级后 reject 不应再出 DAG |

执行期降级见 [§H](#h-plan-workflow-重试与降级执行期)。

**排查**：orchestrator 日志搜 `[PlanWorkflowExecutor]` / `[WorkflowPlanner]` / `[PlanJsonParser]`；静态 workflow 另搜 `[WorkflowExecutor] 静态工作流 … 物化为 Plan`。

| 现象 | 常见原因 | 处理 |
|------|----------|------|
| `Planner 输出为空` | ① Nacos prompt 含「禁止面向用户的答复」→ flash 返回空 content；② **llm-gateway 语义缓存**曾缓存该空响应（plan 步 **0ms** 即失败） | 更新 planner prompt；`skip_cache: true`；清 Redis `llm:cache:*`；重启 llm-gateway + orchestrator |
| Plan 有步但校验 rejected | 模型用 `config` 非 `params`、非法节点 type、缺 displayName 等 | 已增强 Parser；answer 由引擎固定拼接 |

| # | 提示词 | 说明 |
|---|--------|------|
| A1 | 先检索差旅报销相关制度，再查询待审批报销单，并对每条做合规分析后给出结论 | **主验收句**（制度+财务+合规，三域） |
| A2 | 先查一下年假制度，再帮我看看待审批的请假单有没有问题 | 制度 + 审批 + 分析 |
| A3 | 先检索报销政策，再列出待审批付款，然后逐条审查是否合规 | 「然后」+ 审查 |
| A4 | 分步处理：先知识库找差旅标准，再查财务待审批报销 | 显式「分步」 |
| A5 | 请完整处理待审批差旅报销：先对照制度，再查单据并给出评估结论 | 「完整处理」+ 评估 |
| A6 | 给我一套差旅报销的分析流程：制度检索、待办查询、合规结论 | 「一套…流程」 |

### A 类负例（不应走 plan-workflow）

| # | 提示词 | 预期 |
|---|--------|------|
| A-N1 | 先帮我写一封邮件再总结一下 | 仅结构句式，**领域组不足** → L3 LLM 或 react |
| A-N2 | 多步介绍一下你自己 | 有「多步」但无配置领域组 → 不走 L1 |

---

## B. 静态 WORKFLOW — finance-list（L2 黄金规则）

**预期 intent after**：「…将按「财务待办查询」流程处理」

**UI**：intent 后出现 **执行计划 + DAG**（`planId=`）；链摘要含「查询待审批财务消息 → 生成回答」；**无**逐步 node 卡片。

| # | 提示词 | ruleId |
|---|--------|--------|
| B1 | 有哪些待审批报销 | `rule-finance-list-pending` |
| B2 | 查询待审批报销单 | 同上 |
| B3 | 列出待审批的差旅报销 | 同上 |
| B4 | 待审批付款有哪些 | 同上 |

---

## C. 静态 WORKFLOW — finance-smart（L2）

**预期 intent after**：「…将按「财务智能分析」流程处理」（以 catalog displayName 为准）

**UI**：Plan DAG 三节点链（查询 → 智能体分析 → 生成回答）。

| # | 提示词 | ruleId |
|---|--------|--------|
| C1 | 待审批报销是否合规 | `rule-finance-smart-compliance` |
| C2 | 这笔报销合规吗 | 同上 |
| C3 | 帮我把待审批单据和制度对比一下 | L3 LLM 倾向 finance-smart（规则需连续匹配「对比制度」） |

---

## D. 静态 WORKFLOW — knowledge-qa（L2）

**预期 intent after**：「…将按「知识库问答」流程处理」（catalog `displayName`）

**UI**：Plan DAG 两业务节点（检索知识库 → 生成回答）。验收句：`年假可以请几天` / `项目预算超支了还能安排出差吗`。

| # | 提示词 | ruleId |
|---|--------|--------|
| D1 | 项目预算超支了还能安排出差吗 | `rule-knowledge-budget-travel` |
| D2 | 出差预算不够怎么办 | 同上 |
| D3 | 预算和出差冲突怎么处理 | 同上 |

---

## E. Skill 绑定（L0，优先于一切）

> **前缀 SSOT**：**`@` = Skill**（本节）；**`#` = Workflow**（见 [§I Workflow # 绑定](#i-workflow-绑定l0)）。二者首字符互斥。

六种触发 SSOT 见 [multi-agent plan §三](../superpowers/plans/2026-06-19-multi-agent-architecture.md#三六种-skill-触发流程)。

| # | 提示词 | 预期 |
|---|--------|------|
| E1 | `@policy-review` 审查这条报销 | `REACT` + skill=policy-review（**流程 1** 单步） |
| E2 | 请使用 compliance-check skill 处理待审批单据 | `REACT` + skill=compliance-check（**流程 2**） |
| E3 | `@finance-analysis 先查制度再拉待办再分析再润色` | `PLAN_WORKFLOW` + skill=finance-analysis + `plannerMode=skill-driven`（**流程 5B**）；intent 后出 Plan DAG |
| E4 | `@finance-analysis 这笔报销是否合规` | `REACT` + skill=finance-analysis；**不得**走 finance-smart（L0 压过 L2） |

### E-Live（5B 执行期）

自动化脚本（推荐）：

```bash
python scripts/verify_skill_5b_live.py
# 本地: GATEWAY_URL=http://localhost:8000 python scripts/verify_skill_5b_live.py
```

| 现象 | 含义 |
|------|------|
| `intent.metadata.plannerMode=skill-driven` | L0 多步 @ 路由 5B 成功 |
| `intent.metadata.skillId=finance-analysis` | Skill 已锁定 |
| plan 步 `detail` 含 `planId=` | Planner 产出合法 DAG（可展示 PlanWorkflowPanel） |
| orchestrator 日志 `[WorkflowPlanner]` 且 user 含「Skill 正文」 | 5B Planner 已读 L2 overlay |
| Planner 失败 | 降级 ReAct（见 §A 降级路径）；脚本 exit 1 |

**3.12 `/skills` 管理页 Live**（与 §E 互补，验 Admin API + UI 手验）：

```bash
python3 scripts/verify_skills_ui_live.py
# 本地: GATEWAY_URL=http://localhost:8000 python3 scripts/verify_skills_ui_live.py
```

---

## I. Workflow `#` 绑定（L0，阶段四 4.13）

> **详设**：[workflow-studio-design.md](../superpowers/specs/2026-06-25-workflow-studio-design.md) §3  
> **与 §E 区分**：**`#` 仅 Workflow** · **`@` 仅 Skill**；首字符互斥，均优先于 L1/L2/L3。

| # | 提示词 | 预期 |
|---|--------|------|
| I1 | `#knowledge-qa 年假可以请几天` | `WORKFLOW` workflowId=knowledge-qa；`reason=workflow:#mention`；Plan DAG |
| I2 | `#knowledge-qa 报销流程是什么` | `WORKFLOW` workflowId=knowledge-qa（Nacos 内置，DB 未覆盖时） |
| I3 | `#finance-smart 待审批报销是否合规` | `WORKFLOW` workflowId=finance-smart；**压过** L2 规则 / L3 自动选型 |
| I4 | `#not-exists 测试` | HTTP 400；文案指向 `/workflows` |
| I5 | `@knowledge-qa 测试` | **不得**当 workflow；按 Skill 解析 → 未知 Skill 400 或 none |

**边界**：

| 提示词 | 必须 **不是** | 必须是 |
|--------|--------------|--------|
| I1 | `REACT` / 无 `#` 时 L3 自选 | `WORKFLOW` + `#` 显式 knowledge-qa |
| I3 | finance-smart 仅靠 L2 命中 | `WORKFLOW` + `#` 显式锁定 |
| E4 `@finance-analysis …` | `WORKFLOW` | 仍为 Skill L0（§E 不变） |

---

## J. Chat `executionPreference` 强制路由（P0 ✅）

> **详设**：[chat-execution-mode-selector-design.md](../superpowers/specs/2026-06-25-chat-execution-mode-selector-design.md)  
> **请求**：SSE 发送体 `executionPreference`（`auto` \| `simple-llm` \| `react` \| `workflow` \| `plan-workflow`）  
> **边界**：本节约 **执行路径**；**指定 workflow 模板**用正文 `#id`（4.13 §I），**不在底栏做 catalog 下拉**。

| # | preference | 提示词 | 预期 mode | @skill |
|---|------------|--------|-----------|--------|
| J1 | `simple-llm` | 写一段快速排序 | `SIMPLE_LLM`；`reason=user:forced-simple-llm` | ❌ |
| J2 | `react` | 待审批是否合规 | `REACT`；`reason=user:forced-react` | ✅ |
| J3 | `workflow` | 年假可以请几天 | `WORKFLOW` knowledge-qa | ❌ |
| J4 | `plan-workflow` | 先查制度再查待审批 | `PLAN_WORKFLOW`；`reason=user:forced-plan-workflow` | ✅ |
| J5 | `workflow` | `@policy-review 年假可以请几天` | `WORKFLOW` knowledge-qa；**忽略** @skill（strip 正文） | ❌ |
| J6 | `plan-workflow` | `@finance-analysis 是否合规` | `PLAN_WORKFLOW` + `params.skillId=finance-analysis`（**保留** forced mode，仅合并 L0 params） | ✅ |

单测：`ForcedExecutionRouterTest` · `ExecutionPlanRouterTest` · `RoutingGoldenSetTest#forcedJ*`

Live：`python scripts/verify_execution_preference.py`

---

## F. LLM 兜底（L3）与 Skill 自动发现（流程 3）

规则与结构均未命中时走 `IntentRouter`（短句可能先 intent 改写）。`REACT` 产出后 **`SkillDiscoveryService`** 按 catalog 摘要匹配 skill（`reason=skill:auto-discovered`）。

| # | 提示词 | 预期（典型） |
|---|--------|--------------|
| F1 | 随便聊聊 | `REACT` 或 `SIMPLE_LLM`；**无** skill 绑定 |
| F2 | 年假可以请几天 | `WORKFLOW` knowledge-qa（LLM 选 catalog） |
| F3 | 待审批 | 短句 → intent 改写后分类（见 timeline detail） |
| F4 | 帮我做一笔报销的合规分析 | L3→`REACT` + skill=finance-analysis（**流程 3** 自动发现） |

---

## G. 边界对照（防回归）

| 提示词 | 必须 **不是** | 必须是 |
|--------|--------------|--------|
| A1 主验收句 | finance-list | PLAN_WORKFLOW |
| B1 有哪些待审批报销 | PLAN_WORKFLOW | finance-list |
| C1 待审批报销是否合规 | finance-list | finance-smart |
| A1 + 去掉「先…再…」改为逗号串联 | 若 L1 未命中且含「查询待审批」 | 曾误路由 finance-list；L2 保险丝 + 配置 domain 组应避免 |
| E4 `@finance-analysis 是否合规` | finance-smart | `REACT` + skill=finance-analysis |
| E3 `@finance-analysis 先…再…` | 仅 REACT 单 Agent | `PLAN_WORKFLOW` 5B + Plan DAG |
| F4 帮我做合规分析 | finance-smart / workflow | `REACT` + skill 自动发现（无 @） |

---

## H. Plan-Workflow 重试与降级（执行期）

> **详设**：[plan-workflow-retry-degradation.md](./plan-workflow-retry-degradation.md)

| # | 场景 | 提示词 / 操作 | 预期 |
|---|------|---------------|------|
| H1 | 基线成功 | A1 主验收句 | `completed`；DAG + `node-answer` |
| H2 | 节点重试 | A1 + 短暂停 tool-manager/finance | DAG 角标 `×2`；`attempts[]` |
| H3 | 关键 tool fail_fast | A1 + 停 finance | `failed`；不进入 `completed_with_errors` |
| H4 | 非关键 continue | 部分 tool 失败且 `on-failure: continue` | `completed_with_errors`；answer 含上游失败行 |
| H5 | fallback_react | 同上 + Nacos `critical-on-failure: fallback_react` | `degraded_react`；接续 ReAct |
| H6 | ReAct overlay | 「查制度 + 列待审批 + 对比超标」走 ReAct | 工具失败时模型可改参再调（非引擎重试） |

单测：`mvn test -pl orchestrator -Dtest=PlanWorkflowExecutorTest,WorkflowExecutorTest,NodeRetryExecutorTest`

---

## F. ReAct TaskBoard（阶段四 · 4.7.5）

> **状态**：⬜ 阶段四 4.7.5 实施后启用  
> **详设**：[react-taskboard-design.md](../superpowers/specs/2026-06-24-react-taskboard-design.md) · **锁定 D11** · 配置键 `agent.execution.react.taskboard.*`

**前置**：`agent.execution.react.taskboard.enabled=true`；Nacos 已同步并重启 orchestrator。

**预期**：`react` 路径出现 **`tasks` 步**（phase=`tasks`），`metadata.tasks[]` 含任务项；**与 `plan` / Plan DAG 互斥**。

### F1. 正例（应有 `tasks`）

| # | 提示词 | 预期 mode | 预期 UI |
|---|--------|-----------|---------|
| F1 | 帮我查待审批报销，并对有风险的单据逐条说明原因 | react | `tasks` ≥2 项 → tool* → generate |
| F2 | 用财务工具汇总各状态数量，并解释异常偏多的状态 | react | `tasks` 含汇总+解释 |
| F3 | 搜知识库看差旅标准，再帮我看看有没有超标的风险点 | react（若 L1 未命中） | `tasks` + rag/tool 链 |

### F2. 负例 / 边界

| # | 提示词 / 条件 | 预期 |
|---|---------------|------|
| F-N1 | A1 主验收句 | **plan-workflow** + Plan DAG；**无** `tasks` |
| F-N2 | `taskboard.enabled=false` | 与阶段三 ReAct 一致 |
| F-N3 | 你好 | 可无 `tasks` 或 0 项 |
| F-N4 | H5 plan 降级 ReAct | 可有 ReAct 链；**无** Plan DAG；TaskBoard 可选 |

### F3. Timeline（成功路径）

```
识别意图 → …将由自主智能体分析并作答
任务清单 → 正在执行：{activeTask}   （metadata.tasks[]）
规划推理 → think*
工具调用 → tool-*
…
撰写回复 → generate
```

### 单测（阶段四）

```bash
mvn test -pl orchestrator -Dtest=ReactTaskBoardTest,ManageTasksToolTest,RoutingGoldenSetTest
```

---

## 配置变更指引

| 需求 | 改哪里 |
|------|--------|
| 新增多步句式 | `agent.routing.structural.multi-step-patterns` |
| 新增跨领域信号 | `agent.routing.structural.domain-groups` 增组或扩词 |
| 新增单域快路径 | `agent.routing.rules` 追加 rule（勿与 structural 重复 plan 规则） |
| 意图步文案 | `agent.timeline.intent.modes` |
| 语义兜底 | `agent.intent.classifier-prompt` |
| Plan 重试/降级/Replan | `agent.execution.plan-workflow` · 见 [plan-workflow-retry-degradation.md](./plan-workflow-retry-degradation.md) |
| Skill `@` / 强提示 / 5B | `agent.skill.hint-patterns`；L0 多步→`plannerMode=skill-driven` |
| Skill 自动发现阈值 | `SkillDiscoveryService`（catalog description bigram 打分） |
| 第五模式 peer 模板 / 句式（阶段四） | `agent.peer.templates` / `agent.routing.peer.structural-patterns` · 见 [peer-collab spec](../superpowers/specs/2026-06-24-peer-collab-routing-design.md) |
| ReAct TaskBoard（阶段四） | `agent.execution.react.taskboard.*` / `agent.timeline.steps.tasks` / `mode-overlays.react` · 见 [taskboard spec](../superpowers/specs/2026-06-24-react-taskboard-design.md) |
| Chat 强制执行模式 | 请求体 `executionPreference`；intent 文案 `agent.timeline.intent.modes.*.forced-after` · 见 [chat selector spec](../superpowers/specs/2026-06-25-chat-execution-mode-selector-design.md) |
| Workflow 模板 / `#` 绑定 | `workflow-manager` catalog + L0 `#` · **非**底栏二级下拉 · 见 [workflow-studio spec](../superpowers/specs/2026-06-25-workflow-studio-design.md) §3 |

改完：`sync_nacos.py` → 重启 orchestrator → 跑上表至少 A1/B1/C1/D1 + G 对照。

---

## E. PEER_COLLAB（阶段四 · 第五顶层模式）

> **状态**：⬜ 阶段四 4.7.3 实施后启用  
> **详设**：[peer-collab-routing-design.md](../superpowers/specs/2026-06-24-peer-collab-routing-design.md) · **锁定 D10** · 配置键 `agent.routing.peer.*` / `agent.peer.templates`

**预期 intent after**：「…将由多专家协作交叉验证」（`agent.timeline.intent.modes.peer-collab`）

### E1. 应对 → `peer-collab`

| 提示词 | 必须 **不是** | 必须是 |
|--------|--------------|--------|
| 请制度专家和财务专家分别审查这笔报销是否合规，并互相验证 | plan-workflow / finance-smart | **peer-collab** |
| 从合规和财务两个角度交叉审查上述制度条款 | workflow | **peer-collab** |

### E2. 应对 → 仍 `plan-workflow`（边界对照）

| 提示词 | 必须是 | 说明 |
|--------|--------|------|
| 先检索报销制度，再查待审批列表，并对结果做合规分析 | plan-workflow | 结构化流水线，非对等协商 |

### E3. Timeline（成功路径）

```
识别意图 → 将由多专家协作交叉验证
peer-collab → …（压缩摘要，无 MsgHub 多轮 raw 对话）
生成回答 → …
```

- transcript 仅 audit / Plan 类详情页可查，**不上**主 SSE 逐步卡片
- 失败降级：intent 步或 peer 步说明改走 plan-workflow / react

### 单测（阶段四）

```bash
mvn test -pl orchestrator -Dtest=RoutingGoldenSetTest#peerCollab*
```
