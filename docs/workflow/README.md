# Workflow 标杆种子（MySQL init）

平台 **11 条标杆 workflow** 的唯一静态 SSOT：`docker/mysql/init/13-sunshine-workflow-manager.sql`（可由 `scripts/sync_enterprise_workflows.py --write-sql` 从 `enterprise_workflow_plans.py` 重生成 INSERT）。

> **运行时 SSOT**：`workflow-manager` DB（Studio 发布 / CRUD）。init SQL 仅用于**新环境初始化**；已部署库改标杆须跑 `python3 scripts/sync_enterprise_workflows.py`（UPSERT + `PUBLISH workflow-catalog-changed`）。
>
> **4.13 状态**：当前形态（线性 + 并行 + exclusive + loop）**已收口**；企业读写三条见 [enterprise-workflows 详设](../superpowers/specs/archive/2026-07-21-enterprise-workflows-design.md)。

## 标杆清单

| workflowId | displayName | 说明 |
|------------|-------------|------|
| `knowledge-qa` | 知识库问答 | 单路制度 RAG（青松假/网约车等 corpus-50 锚点） |
| `knowledge-dual` | 双路知识检索 | 并行双 RAG + join（人事/费用；4.7.2） |
| `knowledge-branch` | 条件分支知识检索 | exclusive-gateway：含「报销」→ 财务 RAG，否则人事（4.13.7） |
| `knowledge-loop` | 条件循环知识检索 | do-while：rag→报销列表→假期余额→agent；含「继续」再轮（4.13.7） |
| `finance-list` | 我的报销查询 | 仅列出当前用户报销单 |
| `finance-summary` | 报销汇总统计 | 按状态汇总条数与金额 |
| `finance-smart` | 报销智能分析 | 只读合规：RAG→list→agent（不可 submit） |
| `sandbox-agent` | 工作区沙箱写文件 | agent（默认沙箱工具）→ answer（4.5 SUB 标杆） |
| `hr-leave-assist` | 假期助手 | RAG→余额/请假单→agent（可 submit_leave，HITL） |
| `expense-compliance` | 费用合规闭环 | RAG→list→agent（可 submit_expense，HITL） |
| `oa-task-assist` | OA 待办助手 | list→agent（可 approve_oa_task，HITL） |

## 种子约定

| 项 | 约定 |
|----|------|
| 条数 | **11** 条，`source=seed`，`active_version=1`，`status=published`，`enabled=1` |
| 节点 ID | 业务节点 `{type}-{8位hex}`；`start` / `answer` 固定 |
| RAG | **必填** `params.query`（默认 `{{start.userQuery}}`） |
| Agent | **必填** `params.query`；有上游时 **必填** `params.context` |
| Tool | Catalog ID（`sdk__*` / `mcp__*`）；写工具仅挂 agent `tools` 白名单 |
| 执行策略 | 各业务节点显式 `retry.maxAttempts` / `retry.backoffMs` / `retry.onFailure` |
| 下游引用 | `{{node-id.output}}` · `{{node-id.answer}}`（agent）；loop 出框用 `{{loop-id.output}}` |
| 画布坐标 | **必填** 顶层 `layout`：`{ "node-id": { "x": number, "y": number } }`（与 Studio 自动布局一致） |

## 初始化（新环境）

1. MySQL 执行 `docker/mysql/init/13-sunshine-workflow-manager.sql`
2. 启动 `workflow-manager` :8230
3. orchestrator 经 `WorkflowManagerClient` 读 DB — **无需** Nacos workflow

## 维护

修改标杆 workflow 时：

1. 编辑 **`scripts/enterprise_workflow_plans.py`**（PlanJson SSOT）
2. `python3 scripts/sync_enterprise_workflows.py --write-sql` 更新 init SQL INSERT
3. `python3 scripts/sync_enterprise_workflows.py` 同步 Live（UPSERT 新 version + Redis PUBLISH）
4. 或对已运行环境手工 UPDATE 后 `redis-cli -h ecs4c16g PUBLISH workflow-catalog-changed default`

业务自助 workflow 经 **`/workflows` Studio** 或 `POST /api/workflows/import` 维护，不写入 init SQL。

## 详设

- [workflow-studio-design.md](../superpowers/specs/archive/2026-06-25-workflow-studio-design.md)
- [2026-07-21-enterprise-workflows-design.md](../superpowers/specs/archive/2026-07-21-enterprise-workflows-design.md)
- [2026-07-11-workflow-studio.md](../superpowers/plans/2026-07-11-workflow-studio.md)
