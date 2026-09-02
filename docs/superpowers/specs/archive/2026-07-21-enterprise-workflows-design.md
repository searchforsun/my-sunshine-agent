# 企业工作流标杆深化 + 新建三条（方案 1）

> **状态**：✅ 已实现（Live sync + 验收脚本；2026-07-21）  
> **范围**：深化现有 8 条 workflow 标杆 + 新建 3 条企业读写流程；Live 同步；验收脚本对齐 corpus-50  
> **决策**：方案 **C（深化+新建）** · 写路径 **C（读写均开，HITL）** · 实现路线 **方案 1（增量，不改旧 id）**  
> **实现计划**：[plans/2026-07-21-enterprise-workflows.md](../../plans/2026-07-21-enterprise-workflows.md)  
> **关联**：[corpus50-platform-adapt](./2026-07-21-corpus50-platform-adapt-design.md) · [workflow-studio](./2026-06-25-workflow-studio-design.md) · [tool-integration HITL](./2026-07-09-tool-integration-design.md) · Skills SSOT `docs/skills/`

---

## 0. 问题与目标

### 0.1 现状

- Live Catalog 仍残留 demo 文案（如「年假可以请几天」「财务待办查询」），与 init 种子部分漂移。
- 8 条标杆多为短链（单 tool/rag → answer），几乎未接入 HR / OA；企业场景偏薄。
- Live 可能缺 `finance-smart` 等定义，与 `13-*.sql` 不一致。

### 0.2 目标

1. **深化**现有 8 个 `workflowId`（不改名）：corpus-50 锚点文案 + 合理多节点（RAG / HR / 费用）。
2. **新建** 3 条企业流程：`hr-leave-assist`、`expense-compliance`、`oa-task-assist`（只读拉取 + Agent 按需写工具 + HITL）。
3. **同步 Live**（UPSERT + Catalog 刷新），并 **更新验收脚本**。
4. 仓内/Live **无**旧 demo 验收句与旧工具 Catalog ID；禁止别名兼容层。

### 0.3 非目标

- 不改 Workflow 引擎拓扑语义；不新增第 5 种 knowledge 拓扑 id。
- 不做旧 id 重命名 / 别名映射。
- 不把业务种子数据写入 workflow SQL。
- 不改 RAG 主链路。

---

## 1. 决策摘要

| # | 议题 | 决策 |
|---|------|------|
| 1 | 范围 | 深化 8 + 新建 3；同步改 verify_* |
| 2 | 写操作 | 读写均开；写工具经 Agent 白名单按需调用 + Catalog HITL |
| 3 | 实现路线 | 方案 1：保留旧 id，增量深化 |
| 4 | `finance-smart` vs `expense-compliance` | 前者 **只读分析**；后者 **分析 + 可 submit_expense** |
| 5 | 身份 | 仅 Gateway `x-user-id` / `x-tenant-id`；禁止 plan/LLM 传 `userId` |
| 6 | SSOT | `docker/mysql/init/13-sunshine-workflow-manager.sql` + sync 脚本 |

---

## 2. Catalog 职责划界（11 条）

### 2.1 保留并深化（8）

| id | 展示名（目标） | 定位 | 主要变化 |
|----|----------------|------|----------|
| `knowledge-qa` | 知识库问答 | 单路制度 RAG | examples / description → 青松假、网约车、锁钥通道等 |
| `knowledge-dual` | 双路知识检索 | 并行双 RAG + join | examples 去 demo；两路 displayName 区分人事/费用；query 可加轻量域前缀 |
| `knowledge-branch` | 条件分支知识检索 | exclusive-gateway | 边条件扩「报销/费用/网约车」→ 财务 RAG，否则人事；验收句换锚点 |
| `knowledge-loop` | 条件循环知识检索 | do-while：rag→tools→agent | 框内增加 `get_leave_balance`（与 `list_my_expenses` 并列） |
| `finance-list` | 我的报销查询 | 仅列表 | 文案企业化；工具 `list_my_expenses` |
| `finance-summary` | 报销汇总统计 | 汇总 | `summarize_my_expenses`；examples 企业化 |
| `finance-smart` | 报销智能分析 | **只读**合规分析 | list 前加 RAG（费用制度）；agent=`finance-analysis`；**不**挂 submit |
| `sandbox-agent` | 工作区沙箱写文件 | 4.5 标杆 | 文案去「演示」感；拓扑不变 |

### 2.2 新建（3）

| id | 展示名 | 链路 |
|----|--------|------|
| `hr-leave-assist` | 假期助手 | `rag` → `get_leave_balance` → `list_leave_requests` → `agent`(skill=`policy-review`, tools=`submit_leave_request`) → `answer` |
| `expense-compliance` | 费用合规闭环 | `rag` → `list_my_expenses` → `agent`(skill=`compliance-check`, tools=`list_my_expenses,get_expense_detail,submit_expense`) → `answer` |
| `oa-task-assist` | OA 待办助手 | `list_oa_tasks` → `agent`(tools=`approve_oa_task`, overlay=仅审批用户点名的 taskId) → `answer` |

工具 Catalog ID（完整）：

- `sdk__sunshine-hr__get_leave_balance` / `list_leave_requests` / `submit_leave_request`
- `sdk__sunshine-finance__list_my_expenses` / `get_expense_detail` / `submit_expense`
- `sdk__sunshine-oa__list_oa_tasks` / `approve_oa_task`

---

## 3. 写工具契约（按需 + HITL）

### 3.1 为何不用固定 tool 写节点

静态 **tool 节点必跑**。若固定挂 `submit_*` / `approve_*`，无「提交意图」时仍会触发写调用与 HITL，体验差且难验收。

### 3.2 约定（推荐，本设计默认）

1. **只读拉取**用固定 `tool` / `rag` 节点。
2. **写工具**仅放入下游 **agent** 的 `tools` 白名单。
3. Agent overlay 约定：仅当用户明确「提交 / 申请 / 审批 / 批准」且参数齐全时调用写工具；否则只输出只读结论。
4. Catalog `sideEffect=write` + `require_confirmation` → 现有 Workflow `ToolNodeHandler` / Agent 工具 HITL（与 3.3 / 4.8 一致）。
5. 写工具参数从用户话术与上游 output 解析；**禁止**虚构 taskId / expenseId / 金额。

### 3.3 可选加深（非必须）

`knowledge-branch` 式 exclusive：问句含「提交|申请|批准」走写分支。本轮新建三条 **不依赖**该拓扑，以免与引擎验收标杆耦合。

---

## 4. 深化细节（现有 8 条）

### 4.1 文案与 examples（统一）

- 禁止：`年假可以请几天`、`leave-policy-v1`、`list_finance_messages`、纯 demo「待审批财务消息」口径（若产品语义已是「我的报销」须一致）。
- 推荐 examples（可按流程裁剪）：
  - `#knowledge-qa 青松假有多少天、怎么申请`
  - `#knowledge-qa 市内网约车报销上限多少`
  - `#knowledge-dual 青松假和网约车报销上限一起查`
  - `#knowledge-branch 网约车报销需要哪些材料` / `青松假怎么申请`
  - `#knowledge-loop 分析青松假余额和我的待报销`（及「继续…」）
  - `#finance-list 我有哪些待报销`
  - `#finance-smart 对照制度看我的报销是否合规`
  - `#hr-leave-assist 青松假还有几天，我的请假单呢`
  - `#expense-compliance 网约车上限对照我的报销，必要时帮我提交一笔`
  - `#oa-task-assist 我的 OA 待办有哪些`

### 4.2 `knowledge-branch` 边条件

- 财务分支 `contains` 右侧扩为可覆盖：至少「报销」；实现时可改为多条件边或右侧关键字串与 Live 验收一致（建议：`报销` 保留为主条件，examples 用「网约车报销…」命中；若需「费用/网约车」另增边，须改 verify exclusive 断言）。
- **本轮最小变更**：保留 `contains「报销」` 主条件（已有 Live），description/examples 企业化；若 exclusive 验收句已用「网约车报销」则无需改边。

### 4.3 `knowledge-loop`

框内顺序建议：

```text
rag → tool(list_my_expenses) → tool(get_leave_balance) → agent(finance-analysis + 只读 tools) →（loop 条件）
```

`agent.context` 注入两路 tool output + rag。layout 加宽以容纳第二 tool 节点。

### 4.4 `finance-smart`

```text
start → rag(费用/差旅/网约车) → tool(list_my_expenses) → agent(finance-analysis, 只读 tools) → answer
```

### 4.5 `knowledge-dual`

两路 RAG `displayName`：`人事制度检索` / `费用制度检索`；`query` 可选前缀（如「人事制度：{{start.userQuery}}」），避免两路完全同 query 时仍满足并行拓扑验收。

### 4.6 `sandbox-agent`

仅改 displayName/description/answer 约束中的「演示」措辞；节点图不变。

---

## 5. SSOT 与 Live 同步

| 产物 | 动作 |
|------|------|
| `docker/mysql/init/13-sunshine-workflow-manager.sql` | 改写 8 条 INSERT；新增 3 条 definition+version |
| `docs/workflow/README.md` | 标杆清单 8 → 11 |
| `scripts/sync_enterprise_workflows.py`（新建） | 对 `sunshine_workflow` UPSERT（按 id）；`active_version` bump 或同版本替换 published plan；`redis-cli PUBLISH workflow-catalog-changed default` |
| 路由 / Chat 空态 / golden-set | 按需补 `#` 示例与锚点；不强制大改 L1 规则集 |
| Prompt Catalog | 非必须；若有 workflow 示例句则换锚点 |

**同步原则**：init SQL 为新环境 SSOT；已部署库 **必须**跑 sync，禁止只改 SQL 不刷 Live。

**版本策略（建议）**：Live 对每个 id 写入 **新 version**（`active_version+1`）并 publish，便于 diff；或 sync 覆盖当前 published `plan_json`（实现计划里二选一，默认 **新 version**）。

---

## 6. 验收

### 6.1 脚本改动

| 脚本 | 改动 |
|------|------|
| `verify_workflow_studio_live.py` | catalog 期望 11 id；hash/examples 锚点；可选 suite 抽测新 3 条只读路径 |
| `verify_exclusive_gateway_live.py` / `verify_loop_live.py` | 委托同上；验收句保持/对齐 corpus-50 |
| `verify_hitl_live.py` | 可选扩展写路径；或由新脚本承担 |
| **新建** `verify_enterprise_workflow_live.py` | `--suite read\|write\|all`：E1–E3 只读；E4–E6 写工具出现 confirmation |
| `phase2_agent_demo.py` / `verify_tenant_live.py` / expert 相关 | 去掉旧 demo 句；id 引用仍有效 |

### 6.2 检查门

| ID | 标准 |
|----|------|
| W1 | Catalog 含 11 个 id；无 demo 句「年假可以请几天」 |
| W2 | `#knowledge-qa 青松假…` 命中制度内容 |
| W3 | `#knowledge-branch` / `#knowledge-loop` / parallel Live 绿 |
| W4 | `#hr-leave-assist` 出现余额/请假数据（alice） |
| W5 | `#expense-compliance` 只读路径出合规分析；写路径触发 HITL |
| W6 | `#oa-task-assist` 列表 + 审批写路径 HITL |
| W7 | `finance-smart` 无 submit；与 `expense-compliance` 描述划界清晰 |
| W8 | rg 业务路径无 `list_finance_messages` / `leave-policy-v1` |

---

## 7. 落地顺序

1. 改 `13-*.sql` + `docs/workflow/README.md`（含 3 条新 PlanJson）
2. 实现 `sync_enterprise_workflows.py` 并执行 Live
3. 改 `verify_workflow_studio_live` + 新建 `verify_enterprise_workflow_live`
4. 跑 W1–W8；修漂移
5. 按需改 golden-set / Chat 空态

---

## 8. 风险

| 风险 | 缓解 |
|------|------|
| Agent 误调写工具 | overlay 严约束 + HITL 兜底；write suite 断言须出现 confirmation |
| `knowledge-loop` 加节点导致 layout/Live 断言变脆 | 同步改 loop suite 节点计数/文案 |
| Live 与 init 再漂移 | sync 可重复执行；README 写明命令 |
| Skill 未启用导致 agent 失败 | 依赖已上线的 `policy-review` / `compliance-check` / `finance-analysis` |

---

## 9. 文档 SSOT

| 产物 | 路径 |
|------|------|
| 本设计 | `docs/superpowers/specs/2026-07-21-enterprise-workflows-design.md` |
| 实现计划 | 审阅通过后：`docs/superpowers/plans/2026-07-21-enterprise-workflows.md` |
| 标杆说明 | `docs/workflow/README.md` |
| 种子 | `docker/mysql/init/13-sunshine-workflow-manager.sql` |
