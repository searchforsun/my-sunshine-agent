# Workflow 定义模板（PlanJson）

与 DB `workflow_version.plan_json` + `workflow_definition` Catalog 元数据**同构**的 JSON 模板，供 **MySQL init 种子编写**、Studio 批量导入、环境迁移参考。

> **运行时 SSOT**：`workflow-manager` DB（**非** Nacos、**非**本目录文件）。

## 文件

| 文件 | workflowId |
|------|------------|
| `knowledge-qa.json` | 知识库问答 |
| `finance-list.json` | 财务待办查询 |
| `finance-smart.json` | 财务智能分析 |
| `finance-summary.json` | 财务汇总统计 |
| `manifest.json` | 批量导入清单 |

## 初始化（新环境）

1. MySQL 执行 `docker/mysql/init/13-sunshine-workflow-manager.sql`（含 4 条 **published v1** 种子）
2. 启动 `workflow-manager` :8230
3. orchestrator 经 `WorkflowManagerClient` 读 DB — **无需** Nacos workflow、**无需**手工导入

## Studio / 迁移

- Studio UI：**导入 JSON** → `PlanValidator` → 草稿 → 发布
- API：`POST /api/workflows/import`
- 工具 ID 须为 Catalog 格式：`sdk__sunshine-finance__*` / `mcp__*`

## 维护

- 修改标杆 workflow：优先在 `/workflows` Studio 发布，或同步更新本目录 JSON + init SQL
- 详设：[workflow-studio-design.md](../superpowers/specs/2026-06-25-workflow-studio-design.md)
