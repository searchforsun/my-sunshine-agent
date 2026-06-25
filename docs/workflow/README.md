# Workflow 导入包（PlanJson）

由 `docs/nacos/sunshine-workflows.yaml` 导出的 **Workflow Studio 可导入 JSON**，与 DB `workflow_version.plan_json` + Catalog 元数据同构。

## 文件

| 文件 | workflowId |
|------|------------|
| `knowledge-qa.json` | 知识库问答 |
| `finance-list.json` | 财务待办查询 |
| `finance-smart.json` | 财务智能分析 |
| `finance-summary.json` | 财务汇总统计 |
| `manifest.json` | 批量导入清单 |

## 导入方式（阶段四 4.13 实现后）

- Studio UI：**导入 JSON** → 校验 `PlanValidator` → 存草稿 → 发布
- API：`POST /api/workflows/import`（multipart 或 JSON body）
- **无 Flyway 种子**；新环境由运维/研发按需导入，或保留 Nacos 内置 workflow 运行

## 与 Nacos 关系

- 运行时默认仍走 Nacos `sunshine-workflows.yaml`（GitOps SSOT）
- 导入 DB 且 `enabled=true` 发布后，**同 ID 覆盖** Nacos（见 workflow-studio-design §9）
- 本目录 JSON 仅作 **迁移 / 模板 / Studio 初始内容**，不自动写入 DB

## 维护

YAML 变更后请同步更新本目录 JSON（后续可提供 `scripts/export_workflows_json.py`）。
