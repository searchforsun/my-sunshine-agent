# 路由 Golden-Set（验收提示词）

> **SSOT**：人工/UI 验收 + `RoutingGoldenSetTest` 单测对照  
> **配置**：`docs/nacos/sunshine-orchestrator.yaml` → `agent.routing.structural` / `agent.routing.rules` / `agent.execution.plan-workflow`  
> **代码**：`ExecutionPlanRouter` → `RoutingPolicyChain`（L0→L3）  
> **重试/降级详设**：[plan-workflow-retry-degradation.md](./plan-workflow-retry-degradation.md)

## 策略链（意图识别步内）

| 层级 | Policy | 配置 | 产出 |
|:----:|--------|------|------|
| L0 | `SkillBindingRoutingPolicy` | `agent.skill.hint-patterns` + `@` 硬编码 | `REACT` + skillId |
| L1 | `StructuralRoutingPolicy` | `agent.routing.structural` | `PLAN_WORKFLOW` |
| L2 | `GoldenRuleRoutingPolicy` | `agent.routing.rules` | 静态 `WORKFLOW` |
| L3 | `LlmClassifierRoutingPolicy` | `agent.intent.classifier-prompt` | LLM 选 mode/workflow |
| L3+ | （阶段四）第五 mode `peer-collab` | 同上 + `agent.routing.peer.*` | 见 [§E](#e-peer_collab阶段四) |

**链规则**：首个返回 `ExecutionPlan` 的策略胜出；L1 命中后 L2/L3 不执行。L2 内仍调用 `StructuralPlanMatcher` 作 L1 漏判保险丝。

**时间线**：上述全部发生在 SSE **`intent`（识别意图）** 步；完成后才进入 `plan` / `node-*` / ReAct 步骤。

## 单测

```bash
mvn test -pl orchestrator -Dtest=StructuralPlanMatcherTest,RoutingGoldenSetTest,ExecutionPlanRouterTest,RuleBasedRouterTest
```

## 验收前准备

1. **新建对话**（或 `python scripts/clear_session_cache.py --force`）
2. Nacos 已同步：`python scripts/sync_nacos.py --data-id sunshine-orchestrator.yaml`
3. orchestrator :8200 已重启

---

## A. PLAN_WORKFLOW（L1 结构守卫）

**预期 intent after**：「…将动态规划多步执行」

### Plan 可视化（成功路径）

意图步之后应出现 **「执行计划」**（`plan` 步），而非 ReAct 的「规划推理」：

```
识别意图 → 将动态规划多步执行
执行计划 → 检索知识库 → 查询待审批… → …（节点链摘要）  [查看详情]
node-n1  → …
node-n2  → …
生成回答 → …
```

- **「查看详情」**：跳转 `/plans/:planId`，展示 Planner JSON、节点 trace、状态（draft/validated/running/completed/**completed_with_errors**/failed/**rejected**/**degraded_react**）
- **不应**在成功路径出现：`规划推理` / `think` / 自主 ReAct 工具链（除非 Planner 失败降级，见下）
- **`think` 仅属 ReAct**：Plan 成功路径 answer 的 reasoning 在 `node-*` 步骤与 Plan 抽屉「综合分析」，**不得**再合成顶层 `think` 行（见 `normalizeTimelineSteps` / `chatSessions`）

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

**排查**：orchestrator 日志搜 `[PlanWorkflowExecutor]` / `[WorkflowPlanner]` / `[PlanJsonParser]`。

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

| # | 提示词 | ruleId |
|---|--------|--------|
| B1 | 有哪些待审批报销 | `rule-finance-list-pending` |
| B2 | 查询待审批报销单 | 同上 |
| B3 | 列出待审批的差旅报销 | 同上 |
| B4 | 待审批付款有哪些 | 同上 |

---

## C. 静态 WORKFLOW — finance-smart（L2）

**预期 intent after**：「…将按「财务智能分析」流程处理」（以 catalog displayName 为准）

| # | 提示词 | ruleId |
|---|--------|--------|
| C1 | 待审批报销是否合规 | `rule-finance-smart-compliance` |
| C2 | 这笔报销合规吗 | 同上 |
| C3 | 帮我把待审批单据和制度对比一下 | L3 LLM 倾向 finance-smart（规则需连续匹配「对比制度」） |

---

## D. 静态 WORKFLOW — knowledge-qa（L2）

**预期 intent after**：「…将按「知识库查询」流程处理」

| # | 提示词 | ruleId |
|---|--------|--------|
| D1 | 项目预算超支了还能安排出差吗 | `rule-knowledge-budget-travel` |
| D2 | 出差预算不够怎么办 | 同上 |
| D3 | 预算和出差冲突怎么处理 | 同上 |

---

## E. Skill 绑定（L0，优先于一切）

| # | 提示词 | 预期 |
|---|--------|------|
| E1 | `@policy-review` 审查这条报销 | `REACT` + skill=policy-review |
| E2 | 请使用 compliance-check skill 处理待审批单据 | `REACT` + skill=compliance-check |

---

## F. LLM 兜底（L3）

规则与结构均未命中时走 `IntentRouter`（短句可能先 intent 改写）。

| # | 提示词 | 预期（典型） |
|---|--------|--------------|
| F1 | 随便聊聊 | `REACT` 或 `SIMPLE_LLM` |
| F2 | 年假可以请几天 | `WORKFLOW` knowledge-qa（LLM 选 catalog） |
| F3 | 待审批 | 短句 → intent 改写后分类（见 timeline detail） |

---

## G. 边界对照（防回归）

| 提示词 | 必须 **不是** | 必须是 |
|--------|--------------|--------|
| A1 主验收句 | finance-list | PLAN_WORKFLOW |
| B1 有哪些待审批报销 | PLAN_WORKFLOW | finance-list |
| C1 待审批报销是否合规 | finance-list | finance-smart |
| A1 + 去掉「先…再…」改为逗号串联 | 若 L1 未命中且含「查询待审批」 | 曾误路由 finance-list；L2 保险丝 + 配置 domain 组应避免 |

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

## 配置变更指引

| 需求 | 改哪里 |
|------|--------|
| 新增多步句式 | `agent.routing.structural.multi-step-patterns` |
| 新增跨领域信号 | `agent.routing.structural.domain-groups` 增组或扩词 |
| 新增单域快路径 | `agent.routing.rules` 追加 rule（勿与 structural 重复 plan 规则） |
| 意图步文案 | `agent.timeline.intent.modes` |
| 语义兜底 | `agent.intent.classifier-prompt` |
| Plan 重试/降级/Replan | `agent.execution.plan-workflow` · 见 [plan-workflow-retry-degradation.md](./plan-workflow-retry-degradation.md) |
| ReAct 工具策略 overlay | `agent.prompt.mode-overlays.react` |
| 第五模式 peer 模板 / 句式（阶段四） | `agent.peer.templates` / `agent.routing.peer.structural-patterns` · 见 [peer-collab spec](../superpowers/specs/2026-06-24-peer-collab-routing-design.md) |

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
