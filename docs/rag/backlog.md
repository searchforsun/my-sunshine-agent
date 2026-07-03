# RAG 未实现功能清单

> **基准**：2026-07-03 代码库 + [README.md](./README.md)  
> **说明**：仅列**当前方案仍有效**且**代码未落地**的项。

---

## 已明确不做 / 已废弃（勿再排期）

| 项 | 处置 |
|----|------|
| 评测顶层三分栏（独立「评测记录」Tab） | **定稿**：2 Tab（运行 \| 脚本）+ 记录内嵌运行页 + 右侧抽屉 |
| `migrate_nacos_config_to_db.py` | **定稿**：配置种子走 `docker/mysql/init/16-sunshine-rag-config-seed.sql` + `config-seed.json` |
| 策略 A/B 对比 `POST /eval/ab` | **不做** |
| 评测周报 Cron | **不做**（按需手动 / CI `rag_eval.py`） |
| MinIO 内置评测集正文 | **定稿**：标准集 SSOT 为 MySQL `eval_suite_item`（`10/11-sunshine-rag-eval-suite*.sql`）；MinIO 仅 Python suite 与报告 |
| Badcase 独立表、per-scope Nacos publish、tenant 配置 merge | 早期方案，已废弃 |

---

## P0 — 4.1 工作台闭环

| # | 功能 | 当前状态 | 建议落点 |
|---|------|----------|----------|
| 1 | **Chat 会话级 kb 选择器** | `KbSelector` 仅在 `/knowledge`；Chat 无 `kbId` 透传 | `ChatView` + session store + orchestrator Chat DTO |
| 2 | **Nacos 业务 publish 彻底移除** | `NacosPublishService` / `ConfigPublishService` / 旧 per-scope API 仍在 | 删 deprecated 端点 + 代码清理 |

---

## P1 — 质量与指标（非新功能）

| # | 项 | 说明 |
|---|-----|------|
| 3 | **v6 相对 vector +15% 门禁** | 阶段三检查门 WARN；通过调参 / 扩评测集解决，非独立开发项 |

---

## P2 — 入库与 OCR（4.2）

| # | 功能 | 当前状态 | 建议落点 |
|---|------|----------|----------|
| 4 | **多格式 ingest 状态机** | 仅有 `IngestJobEntity`；现 `ingest/text` | `IngestJobService` + 状态转移 |
| 5 | **docx / PDF / DashScope OCR** | 无 parser/ocr 包 | `DocxParser`、`DashScopeOcrService` |
| 6 | **入库 Tab UI** | 无 `KbIngestPanel` | 文档 Tab 扩展或第五 Tab |
| 7 | **入库前脱敏** | 未接 desensitize :8600 | confirm 前 RPC |
| 8 | **quarantine 低置信度队列** | 未实现 | OCR 阈值分流 |

---

## P3 — 运维与工程化

| # | 功能 | 当前状态 | 建议落点 |
|---|------|----------|----------|
| 9 | **`scripts/rag_reindex.py`** | 不存在 | 按 kb 全量 re-embed + 进度 |
| 10 | **orchestrator ReAct 统一 RagClient** | `RagTool` 仍调 `KnowledgeRetrievalService` | 对齐 ADR-002 |
| 11 | **tenant 默认 kb 解析** | Chat 未传 kbId 时固定 `default` | orchestrator / rag-service 查 `is_default` |

---

## P4 — 阶段四远期（非 4.1）

| # | 功能 | 状态 |
|---|------|:----:|
| 12 | 文档理解 L2 | ⬜ |
| 13 | 多模态 L3（Vision） | ⬜ |
| 14 | Chat `#kb` | 刻意不做 |
| 15 | RBAC 细分 | 刻意不做 |

---

## 已实现（以代码为准）

**Pipeline / 工作台**

- T0–T0d Pipeline 内聚；T1–T9 四 Tab；T23 `KbWorkbenchContext`
- T24–T25 配置版本 + `EffectiveConfigResolver`；V3 生命周期
- T26–T27 EvalSuite（MySQL 种子）、Suggest、Python runner
- T28：`verify_rag_studio.py`、`rag_eval.py` 调 admin API

**评测 UI（定稿布局）**

- 顶层 **运行评测 \| 评测脚本** 两 Tab
- 运行页内嵌评测记录 + 右侧可拖拽报告抽屉
- 结果视图：概览 / 失败样本 / 调参建议；中文参数标签；**eval_failed** 才可一键应用 → **draft**
- 检索调试「加入评测集」；配置「编辑草稿」拉最新

**存储 SSOT**

- 业务配置：`docker/mysql/init/` + `config-seed.json`
- 内置评测集：`eval_suite` + `eval_suite_item`（MySQL）
- 评测报告 / Python suite：MinIO

---

## 建议实施顺序

```
P0-1 Chat kb 选择器  →  P0-2 清理 Nacos publish 遗留
        ↓
P2-4~8 OCR 入库链（4.2）
        ↓
P3-9 reindex  →  P3-10 orchestrator 统一 RagClient
```
