# 企业工作流标杆深化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 深化现有 8 条 workflow 标杆、新建 3 条企业读写流程（HR/费用/OA），同步 Live，并更新验收脚本对齐 corpus-50。

**Architecture:** PlanJson SSOT 以 Python 字典维护（可渲染进 `13-sunshine-workflow-manager.sql`），经 `sync_enterprise_workflows.py` UPSERT 到 `sunshine_workflow` 并 `PUBLISH workflow-catalog-changed`；写工具仅挂 agent `tools` 白名单，走现有 HITL。

**Tech Stack:** MySQL `sunshine_workflow`、workflow-manager :8230、Gateway SSE、Python `scripts/*.py`、现有 Skills（`policy-review` / `compliance-check` / `finance-analysis`）

**Spec:** [2026-07-21-enterprise-workflows-design.md](../specs/2026-07-21-enterprise-workflows-design.md)

---

## File map

| 文件 | 职责 |
|------|------|
| `scripts/enterprise_workflow_plans.py` | 11 条 workflow 的 definition 元数据 + `plan` dict + `catalog_meta` |
| `scripts/sync_enterprise_workflows.py` | UPSERT Live MySQL、可选 `--write-sql`、Catalog 刷新 |
| `docker/mysql/init/13-sunshine-workflow-manager.sql` | 新环境种子（由 sync `--write-sql` 重写 INSERT 段，或手工对齐） |
| `docs/workflow/README.md` | 标杆清单 11 条 |
| `scripts/verify_workflow_studio_live.py` | `SEED_IDS`→11；文案断言去 demo |
| `scripts/verify_enterprise_workflow_live.py` | E1–E6 企业流程 Live |
| `scripts/verify_hitl_live.py` | 可选指向新写路径（或仅文档互链） |
| `docs/routing/routing-golden-set.md` / Chat 空态 | 按需补 `#` 示例（轻量） |

---

### Task 1: PlanJson Python SSOT（8 深化骨架 + 3 新建）

**Files:**
- Create: `scripts/enterprise_workflow_plans.py`

- [x] **Step 1: 创建模块骨架与常量**

```python
"""11 条企业 Workflow PlanJson SSOT（供 sync / 渲染 init SQL）。"""
from __future__ import annotations

import json
from copy import deepcopy
from typing import Any

TENANT = "default"

# Catalog IDs
T_LIST_EXP = "sdk__sunshine-finance__list_my_expenses"
T_GET_EXP = "sdk__sunshine-finance__get_expense_detail"
T_SUBMIT_EXP = "sdk__sunshine-finance__submit_expense"
T_SUM_EXP = "sdk__sunshine-finance__summarize_my_expenses"
T_LEAVE_BAL = "sdk__sunshine-hr__get_leave_balance"
T_LEAVE_LIST = "sdk__sunshine-hr__list_leave_requests"
T_LEAVE_SUB = "sdk__sunshine-hr__submit_leave_request"
T_OA_LIST = "sdk__sunshine-oa__list_oa_tasks"
T_OA_APPROVE = "sdk__sunshine-oa__approve_oa_task"

RETRY = {
    "retry.maxAttempts": "2",
    "retry.backoffMs": "500",
    "retry.onFailure": "continue",
}
RETRY_FAIL = {
    "retry.maxAttempts": "2",
    "retry.backoffMs": "500",
    "retry.onFailure": "fail_fast",
}


def dumps_plan(plan: dict[str, Any]) -> str:
    return json.dumps(plan, ensure_ascii=False, separators=(",", ":"))


def dumps_meta(meta: dict[str, Any]) -> str:
    return json.dumps(meta, ensure_ascii=False, separators=(",", ":"))
```

- [x] **Step 2: 实现 `WORKFLOWS: list[dict]` 中新建 3 条（完整 plan）**

每条结构：

```python
{
  "id": "hr-leave-assist",
  "displayName": "假期助手",
  "description": "检索假期制度并查询余额/请假单；用户明确申请时可 submit（HITL）",
  "mode": "workflow",
  "plan": { ... },  # nodes/edges/layout
  "catalogMeta": {
    "examples": ["青松假还有几天，我的请假单呢", "帮我申请明天一天青松假"],
    "nodeSummary": ["start", "rag", "tool", "agent", "answer"],
    "intentAfter": "将按假期助手流程处理",
  },
}
```

`hr-leave-assist` 节点（按设计 §2.2）：

```text
start → rag-hr → tool-bal(get_leave_balance) → tool-list(list_leave_requests)
     → agent(skill=policy-review, tools=submit_leave_request,
             systemOverlay=仅当用户明确申请且日期/假别齐全时调用提交；否则只输出只读结论；禁止编造)
     → answer
```

`expense-compliance`：

```text
start → rag-exp → tool-list(list_my_expenses)
     → agent(skill=compliance-check,
             tools=list_my_expenses,get_expense_detail,submit_expense,
             overlay=仅明确提交时写；禁止虚构金额)
     → answer
```

`oa-task-assist`：

```text
start → tool-list(list_oa_tasks)
     → agent(tools=approve_oa_task, overlay=仅审批用户点名的 taskId)
     → answer
```

answer prompt 约束：禁止暴露英文 workflow/tool id；只依据上游 output。

- [x] **Step 3: 从现有 SQL 导入 8 条基线 plan，再按设计深化**

用一次性脚本或手工：从 `13-sunshine-workflow-manager.sql` 解析当前 `plan_json` 进 Python，然后修改：

| id | 深化动作 |
|----|----------|
| `knowledge-qa` | 更新 definition description；`catalogMeta.examples` 用青松假/网约车/锁钥通道 |
| `knowledge-dual` | 两路 displayName=`人事制度检索`/`费用制度检索`；query 前缀 `人事制度：` / `费用制度：` + `{{start.userQuery}}`；examples 企业化 |
| `knowledge-branch` | description/examples 企业化；边条件仍 `contains「报销」`（验收兼容） |
| `knowledge-loop` | 在 `tool-t1o2o3p4` 与 agent 之间插入 `tool-leave`（`get_leave_balance`）；agent.context 含余额；layout 加宽 |
| `finance-list` | displayName/description/examples/answer 改为「我的报销」口径 |
| `finance-summary` | examples 企业化 |
| `finance-smart` | start→rag→list→agent→answer；agent **不含** submit |
| `sandbox-agent` | displayName=`工作区沙箱写文件`；description 去「演示」 |

- [x] **Step 4: 导出校验**

```bash
cd /usr/local/gitproj/my-sunshine-agent
python3 -c "from scripts.enterprise_workflow_plans import WORKFLOWS; assert len(WORKFLOWS)==11; print([w['id'] for w in WORKFLOWS])"
```

Expected: 11 个 id，含 `hr-leave-assist`、`expense-compliance`、`oa-task-assist`。

- [x] **Step 5: Commit**（仅当用户要求提交时执行）

```bash
git add scripts/enterprise_workflow_plans.py
git commit -m "$(cat <<'EOF'
feat(workflow): add enterprise PlanJson SSOT for 11 workflows

EOF
)"
```

---

### Task 2: sync_enterprise_workflows.py

**Files:**
- Create: `scripts/sync_enterprise_workflows.py`
- Modify: `docs/workflow/README.md`

- [x] **Step 1: 实现 UPSERT（新 version 策略）**

```python
#!/usr/bin/env python3
"""将 enterprise_workflow_plans 同步到 Live sunshine_workflow。

用法:
  python3 scripts/sync_enterprise_workflows.py --dry-run
  python3 scripts/sync_enterprise_workflows.py
  python3 scripts/sync_enterprise_workflows.py --write-sql

环境:
  MYSQL_HOST 默认 ecs4c16g；MYSQL_PORT=3306；MYSQL_USER=root；MYSQL_PASSWORD=root123
"""
# 伪代码要点：
# 1. 对每条 WORKFLOWS:
#    INSERT definition ON DUPLICATE KEY UPDATE display_name, description, enabled=1
#    SELECT COALESCE(MAX(version),0)+1 FROM workflow_version WHERE tenant_id/workflow_id
#    INSERT workflow_version (..., status='published', plan_json, catalog_meta, published_at=NOW())
#    UPDATE workflow_definition SET active_version=新版本
# 2. redis-cli PUBLISH workflow-catalog-changed default
# 3. --write-sql: 重写 13 文件中 INSERT 段（保留表结构 DDL）
```

MySQL 连接复用 `sync_corpus50_platform.py` 的 `MYSQL_DEFAULTS` / `mysql_query` 模式。

转义：`plan_json` / `catalog_meta` 写入时对 `\` → `\\`、`'` → `\'`（或用 `mysql` 客户端 `--binary-mode` + 预处理）。

- [x] **Step 2: dry-run 打印将写入的 id 与 version bump**

```bash
PYTHONPATH=scripts python3 scripts/sync_enterprise_workflows.py --dry-run
```

Expected: 列出 11 条 `UPSERT id -> vN`。

- [x] **Step 3: 执行 Live sync + 校验 Catalog**

```bash
PYTHONPATH=scripts python3 scripts/sync_enterprise_workflows.py
# 可选：若 orchestrator 缓存未刷新
redis-cli -h ecs4c16g PUBLISH workflow-catalog-changed default
```

```bash
PYTHONPATH=scripts python3 <<'PY'
import uuid,requests
from sunshine_lib import unwrap_r
gw='http://127.0.0.1:8000'
u='wfs_'+uuid.uuid4().hex[:8]
requests.post(f'{gw}/api/auth/register',json={'username':u,'password':'password123'},timeout=30)
tok=requests.post(f'{gw}/api/auth/login',json={'username':u,'password':'password123'},timeout=30).json()['data']['token']
cats=unwrap_r(requests.get(f'{gw}/api/workflows/catalog',headers={'Authorization':f'Bearer {tok}'},timeout=30).json())
ids=sorted(c['id'] for c in cats)
assert 'hr-leave-assist' in ids and 'finance-smart' in ids
assert all('年假可以请几天' not in str(c.get('examples')) for c in cats)
print('OK', len(ids), ids)
PY
```

Expected: ≥11 ids；无「年假可以请几天」。

- [x] **Step 4: `--write-sql` 更新 init + README**

更新 `docs/workflow/README.md` 标杆表为 11 行（含新建 3 条说明）。

- [x] **Step 5: Commit**（用户要求时）

```bash
git add scripts/sync_enterprise_workflows.py docker/mysql/init/13-sunshine-workflow-manager.sql docs/workflow/README.md
git commit -m "$(cat <<'EOF'
feat(workflow): sync enterprise workflows to Live and init SQL

EOF
)"
```

---

### Task 3: 更新 verify_workflow_studio_live

**Files:**
- Modify: `scripts/verify_workflow_studio_live.py`

- [x] **Step 1: 扩展 SEED_IDS**

```python
SEED_IDS = {
    "knowledge-qa",
    "finance-list",
    "finance-smart",
    "finance-summary",
    "knowledge-dual",
    "knowledge-branch",
    "knowledge-loop",
    "sandbox-agent",
    "hr-leave-assist",
    "expense-compliance",
    "oa-task-assist",
}
```

- [x] **Step 2: 文档字符串「8 标杆」→「11 标杆」；catalog 断言 `SEED_IDS.issubset(ids)`**

- [x] **Step 3: 确认 hash/exclusive/loop 验收句已是 corpus-50；若仍有「年假」则替换**

```bash
rg -n "年假可以请几天|leave-policy-v1|list_finance_messages" scripts/verify_workflow_studio_live.py
```

Expected: 无匹配（或仅注释说明禁止项）。

- [x] **Step 4: 跑 catalog + exclusive + loop**

```bash
PYTHONPATH=scripts python3 scripts/verify_workflow_studio_live.py --suite catalog
PYTHONPATH=scripts python3 scripts/verify_exclusive_gateway_live.py
PYTHONPATH=scripts python3 scripts/verify_loop_live.py
```

Expected: PASS。

- [x] **Step 5: Commit**（用户要求时）

---

### Task 4: verify_enterprise_workflow_live.py

**Files:**
- Create: `scripts/verify_enterprise_workflow_live.py`
- Modify: `CLAUDE.md` 运维脚本表（加一行）

- [x] **Step 1: 实现 read suite（E1–E3）**

复用 `verify_workflow_studio_live` 的 auth / SSE 收集模式（或 `phase2_agent_demo` 的 chat helper）。

| Case | Query | 断言 |
|------|-------|------|
| E1 | `#hr-leave-assist 青松假还有几天，列出我的请假单` | workflowId=`hr-leave-assist`；正文或 step 含余额/请假相关字段非空（登录 alice 或注册用户有种子时跳过硬金额） |
| E2 | `#expense-compliance 对照网约车制度看我的报销是否合规`（只读，勿说提交） | workflowId 命中；出现 tool/agent/answer 步骤 |
| E3 | `#oa-task-assist 我的 OA 待办有哪些` | workflowId 命中；列表或「暂无」合理 |

登录：优先用固定演示用户（若 auth 有 alice）；否则 register 后工具可能空集——断言「流程跑完 + 无内部 id 泄露」即可，数据非空为 soft assert。

- [x] **Step 2: 实现 write suite（E4–E6）**

| Case | Query | 断言 |
|------|-------|------|
| E4 | `#hr-leave-assist 请帮我申请明天一天青松假，事由测试` | SSE 出现 `type=confirmation` 或写工具 step；**勿**在无确认时假定已写入 |
| E5 | `#expense-compliance 请按网约车类别提交一笔 50 元报销，日期今天，备注 live-test` | 同上 HITL |
| E6 | `#oa-task-assist 批准 taskId=...`（先 list 取真实 id，或跳过若空） | HITL；无待办则 SKIP 并打印 |

CLI：

```bash
python3 scripts/verify_enterprise_workflow_live.py --suite read
python3 scripts/verify_enterprise_workflow_live.py --suite write
python3 scripts/verify_enterprise_workflow_live.py --suite all
```

- [x] **Step 3: 跑 read（必须绿）；write 尽力绿或明确 SKIP**

```bash
PYTHONPATH=scripts python3 scripts/verify_enterprise_workflow_live.py --suite read
```

- [x] **Step 4: CLAUDE.md 表格增加该脚本一行**

- [x] **Step 5: Commit**（用户要求时）

---

### Task 5: 轻量文案清理 + 设计状态

**Files:**
- Modify: `docs/routing/routing-golden-set.md`（若仍有年假 demo 句）
- Modify: `sunshine-ui` Chat 空态（若有旧 `#finance-list` demo hint）
- Modify: `docs/superpowers/specs/2026-07-21-enterprise-workflows-design.md` 状态 → ✅ 已实现（本 Task 收尾时）

- [x] **Step 1:**

```bash
rg -n "年假可以请几天|财务待办查询|leave-policy-v1" \
  sunshine-ui/src docs/routing scripts docker/mysql/init/13-sunshine-workflow-manager.sql \
  --glob '!**/superpowers/plans/**' --glob '!**/superpowers/specs/**'
```

业务路径期望 0（specs/plans 历史说明除外）。

- [x] **Step 2: 修残留文案后重跑**

```bash
PYTHONPATH=scripts python3 scripts/verify_workflow_studio_live.py --suite catalog
PYTHONPATH=scripts python3 scripts/verify_enterprise_workflow_live.py --suite read
```

- [x] **Step 3: 更新设计文档状态为已实现；README/CLAUDE 交叉链接**

- [x] **Step 4: Commit**（用户要求时）

---

## Spec coverage checklist

| Spec 项 | Task |
|---------|------|
| 深化 8 条 | T1 |
| 新建 3 条 | T1 |
| 写工具 Agent + HITL | T1 overlay + T4 write |
| sync Live + init SQL | T2 |
| verify studio / exclusive / loop | T3 |
| verify enterprise E1–E6 | T4 |
| 去 demo 句 / W8 rg | T5 |
| docs/workflow README 11 条 | T2 |

## 风险备注

- Live 用户无业务种子时 E1/E3 数据可能为空：read 套件以「流程命中 + 无内部名泄露」为硬门，数据非空为软门。
- write 套件依赖模型是否调用写工具：失败时检查 overlay 与 tool enable，勿在 verify 里伪造 confirmation。
- `knowledge-loop` 加节点后 loop Live 断言若数节点，需同步放宽。
