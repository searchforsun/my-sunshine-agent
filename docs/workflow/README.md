# Workflow 标杆种子（MySQL init）

平台 **7 条标杆 workflow** 的唯一静态 SSOT：`docker/mysql/init/13-sunshine-workflow-manager.sql`。

> **运行时 SSOT**：`workflow-manager` DB（Studio 发布 / CRUD）。init SQL 仅用于**新环境初始化**；已部署库改标杆须 UPDATE `workflow_version` + `redis-cli PUBLISH workflow-catalog-changed default`。
>
> **4.13 状态**：当前形态（线性 + 并行 + exclusive + loop）**已收口**；v1 非目标不做 — 见 [workflow-studio 详设 §11](../superpowers/specs/2026-06-25-workflow-studio-design.md)。

## 标杆清单

| workflowId | displayName | 说明 |
|------------|-------------|------|
| `knowledge-qa` | 知识库问答 | 单路 RAG 问答 |
| `knowledge-dual` | 双路知识检索 | 并行双 RAG + join（4.7.2 标杆） |
| `knowledge-branch` | 条件分支知识检索 | exclusive-gateway 边条件（含「报销」→ 财务 RAG，否则人事 RAG；4.13.7） |
| `knowledge-loop` | 条件循环知识检索 | do-while：首轮必进 rag→tool→agent；含「继续」再轮（最多 2，exit；4.13.7） |
| `finance-list` | 财务待办查询 | tool → answer |
| `finance-smart` | 财务智能分析 | tool + agent → answer |
| `finance-summary` | 财务汇总统计 | tool → answer |

## 种子约定

| 项 | 约定 |
|----|------|
| 条数 | **7** 条，`source=seed`，`active_version=1`，`status=published`，`enabled=1` |
| 节点 ID | 业务节点 `{type}-{8位hex}`；`start` / `answer` 固定 |
| RAG | **必填** `params.query`（默认 `{{start.userQuery}}`） |
| Agent | **必填** `params.query`；有上游时 **必填** `params.context` |
| Tool | Catalog ID（`sdk__*` / `mcp__*`） |
| 执行策略 | 各业务节点显式 `retry.maxAttempts` / `retry.backoffMs` / `retry.onFailure` |
| 下游引用 | `{{node-id.output}}` · `{{node-id.answer}}`（agent）；loop 出框用 `{{loop-id.output}}` |
| 画布坐标 | **必填** 顶层 `layout`：`{ "node-id": { "x": number, "y": number } }`（与 Studio 自动布局一致） |

## 初始化（新环境）

1. MySQL 执行 `docker/mysql/init/13-sunshine-workflow-manager.sql`
2. 启动 `workflow-manager` :8230
3. orchestrator 经 `WorkflowManagerClient` 读 DB — **无需** Nacos workflow

## 维护

修改标杆 workflow 时：

1. 编辑 **`docker/mysql/init/13-sunshine-workflow-manager.sql`** 中对应 `workflow_definition` / `workflow_version` INSERT
2. 对已运行环境 UPDATE `sunshine_workflow.workflow_version`（init 不覆盖已有库）
3. `redis-cli PUBLISH workflow-catalog-changed default` 刷新 orchestrator Catalog

业务自助 workflow 经 **`/workflows` Studio** 或 `POST /api/workflows/import` 维护，不写入 init SQL。

## 详设

- [workflow-studio-design.md](../superpowers/specs/2026-06-25-workflow-studio-design.md)
- [2026-07-11-workflow-studio.md](../superpowers/plans/2026-07-11-workflow-studio.md)
