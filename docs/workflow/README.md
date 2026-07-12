# Workflow 定义模板（PlanJson）

与 DB `workflow_version.plan_json` + `workflow_definition` Catalog 元数据**同构**的 JSON 模板，供 **MySQL init 种子编写**、Studio 批量导入、环境迁移参考。

> **运行时 SSOT**：`workflow-manager` DB（**非** Nacos、**非**本目录文件）。

## 文件

| 文件 | workflowId | 说明 |
|------|------------|------|
| `knowledge-qa.json` | `knowledge-qa` | 单路 RAG 问答 |
| `knowledge-dual.json` | `knowledge-dual` | 并行双 RAG + join（4.7.2 标杆） |
| `finance-list.json` | `finance-list` | 财务待办查询 |
| `finance-smart.json` | `finance-smart` | 财务智能分析（tool + agent） |
| `finance-summary.json` | `finance-summary` | 财务汇总统计 |
| `manifest.json` | — | 批量导入清单 |

## 种子约定（2026-07-12）

| 项 | 约定 |
|----|------|
| 条数 | **5** 条标杆，`source=seed`，`active_version=1`，`status=published` |
| 节点 ID | 业务节点 `{type}-{8位hex}`（如 `rag-c5d7e903`、`tool-d4e8f901`）；`start` / `answer` 固定 |
| RAG | **必填** `params.query`（默认 `{{start.userQuery}}`）；可选 `context`、`topK`、`kbId` |
| Agent | **必填** `params.query`；有上游时 **必填** `params.context`（如 `{{tool-xxx.output}}`） |
| Tool | `tool` 为 Catalog ID（`sdk__*` / `mcp__*`）；可选 `output.mode` / `output.extract` |
| 执行策略 | 各业务节点显式写入 `retry.maxAttempts` / `retry.backoffMs` / `retry.onFailure` |
| 下游引用 | `{{node-id.output}}` · `{{node-id.answer}}`（agent）· `{{node-id.summary}}` / `{{node-id.parsed.*}}`（tool 提取） |

## 初始化（新环境）

1. MySQL 执行 `docker/mysql/init/13-sunshine-workflow-manager.sql`（含 5 条 published v1 种子）
2. 启动 `workflow-manager` :8230
3. orchestrator 经 `WorkflowManagerClient` 读 DB — **无需** Nacos workflow、**无需**手工导入

## Studio / 迁移

- Studio UI：**导入 JSON** → `PlanValidator` → 草稿 → 发布
- API：`POST /api/workflows/import`
- 工具 ID 须为 Catalog 格式：`sdk__sunshine-finance__*` / `mcp__*`

## 维护与同步（SSOT 三件套）

修改标杆 workflow 时，**须保持同构**（任选入口，但最终三者一致）：

1. **`docs/workflow/{workflowId}.json`** — 编辑 PlanJson 模板
2. **`docker/mysql/init/13-sunshine-workflow-manager.sql`** — 更新对应 `workflow_version` INSERT 的 `plan_json` / `catalog_meta`
3. **已部署 DB** — 对 `workflow_version` v1 执行 UPDATE（init SQL 不覆盖已有库）

推荐流程：

1. 改 `docs/workflow/*.json`
2. 用 JSON 重新生成 SQL INSERT 中的 `plan_json`（保持与模板 `json.dumps(..., separators=(',', ':'))` 一致）
3. 对已运行环境 UPDATE `sunshine_workflow.workflow_version`，并 `redis-cli PUBLISH workflow-catalog-changed default` 刷新 orchestrator Catalog

**禁止**只改 DB 或只改 init SQL 而不同步 `docs/workflow/`。

## 详设

- [workflow-studio-design.md](../superpowers/specs/2026-06-25-workflow-studio-design.md)
- [2026-07-11-workflow-studio.md](../superpowers/plans/2026-07-11-workflow-studio.md)
