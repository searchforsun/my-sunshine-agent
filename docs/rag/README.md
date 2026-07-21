# RAG 四阶段文档索引（SSOT）

> **最后更新**：2026-07-07  
> **代码入口**：`rag-service :8400` · 前端 `/knowledge` · Chat 经 orchestrator `RagClient`  
> **4.1/4.2 检查门留档**：[backlog.md](./backlog.md)（非 live 排期 SSOT）

---

## 1. 四阶段与 RAG 的关系

RAG 能力横跨**阶段三（生产加固）**与**阶段四（平台化）**，不在单一阶段内闭环。

| 阶段 | 周期 | RAG 范围 | SSOT | 状态 |
|:----:|:----:|----------|------|:----:|
| **三** | 8 周 | hybrid+rerank、HyDE、empty-recall、双轨评测门禁、Grafana 可观测 | [phase3-production-hardening-design.md](../superpowers/specs/phase3-production-hardening-design.md) §3.4 | ✅ 检查门通过（v6 +15% 轨 WARN） |
| **四 · 4.0** | 前置 | 检索 pipeline 内聚 rag-service；orchestrator 瘦身 | [ADR-002](../architecture/ADR-002-rag-pipeline-in-rag-service.md) | ✅ |
| **四 · 4.1** | 按需 | `/knowledge` 工作台：多 kb、配置版本、debug、评测、Suggest | 本目录 §2 | ✅ 检查门通过（2026-07-06；G6 WARN 见 backlog 留档） |
| **四 · 4.2–4.4** | 按需 | OCR 入库 L1、文档理解 L2、多模态 L3 | [phase4-platformization-design.md](../superpowers/specs/phase4-platformization-design.md) §4.2–4.4 | ⬜ 未启动 |

总排期摘要：[implementation-plan.md](../implementation-plan.md)

---

## 2. 阶段四 · 4.1 知识库工作台（分层 SSOT）

**原则**：不新增微服务；业务配置 **MySQL 版本化**；线上 Chat 仅读 **active** 配置；Nacos 仅基础设施。

| 层级 | 文档 | 说明 |
|------|------|------|
| **总览** | [2026-06-27-rag-knowledge-studio-design.md](../superpowers/specs/2026-06-27-rag-knowledge-studio-design.md) | 原始全量 spec（4.1+4.2）；§12 指向 V2 |
| **V2 扩展** | [2026-07-01-rag-studio-v2-design.md](../superpowers/specs/2026-07-01-rag-studio-v2-design.md) | 每 kb 独立版本链、MinIO、Suggest、评测平台 |
| **V3 生命周期** | [2026-07-02-kb-config-version-lifecycle-design.md](../superpowers/specs/2026-07-02-kb-config-version-lifecycle-design.md) | draft→pending_eval→evaluating→eval_passed/eval_failed→active |
| **评测 UI** | [2026-07-02-kb-eval-ui-redesign.md](../superpowers/specs/2026-07-02-kb-eval-ui-redesign.md) | 运行/脚本两 Tab + 记录内嵌；Suggest 与应用建议规则 |
| **实施计划** | [2026-06-27-rag-knowledge-studio.md](../superpowers/plans/2026-06-27-rag-knowledge-studio.md) | T0–T28 任务卡与验收 |

**冲突时优先级**：V3 生命周期 > V2 > 父 spec §12 修订项。

---

## 3. 已实现能力（对照代码，2026-07-03）

### 3.1 Pipeline 与运行时（T0–T0d ✅）

- `KnowledgeRetrievalPipeline`：rag 改写 → 检索 → HyDE → empty-recall
- `QueryRewritePipeline` + Nacos `rag.rewrite.*`（基础设施 prompt 模板）
- `EffectiveConfigResolver`：`PRODUCTION` / `DRAFT` / `VERSION(id)`
- orchestrator `RagClient` 扩展 `kbId`、trace（workflow 节点可用）

### 3.2 工作台四 Tab（T1–T9、T23 ✅）

- **文档**：catalog API、版本 supersede、chunk 列表
- **检索调试**：瀑布 trace、`configMode` 透传
- **参数配置**：schema 驱动表单；草稿只读 +「编辑草稿」拉最新；提交评测 / 生效
- **评测**：运行 + 脚本两 Tab；记录在运行页内嵌 + 右侧抽屉

### 3.3 配置版本（T24–T25 ✅）

- `rag_config_bundle` + `rag_config_version`（`docker/mysql/init/14-sunshine-rag-service.sql`）
- 状态机：draft → pending_eval → evaluating → eval_passed / eval_failed → active
- `POST .../apply-suggestions`：**仅 eval_failed** 可应用 → 写入 payload 并 **转为 draft**

### 3.4 评测平台（T26–T27 ✅，部分 T28）

- `eval_suite` + `eval_suite_item`（内置 3 集，见 `14-sunshine-rag-service.sql`）
- `EvaluateService` 异步 job、门禁、`SuggestService`（文本 + 参数建议分离）
- 报告写 MinIO（`EvalReportWriter` + `RagStorageFacade`）
- `PythonEvalRunner`（subprocess 受限）
- 前端：结果三视图（概览/失败/建议）、中文参数标签、eval_failed 一键应用 → draft

### 3.5 运维脚本

| 脚本 | 用途 |
|------|------|
| `scripts/rag_eval.py` | CI / 命令行评测（调 admin eval API） |
| `scripts/rag_ingest_bulk.py` | 批量入库（`--strategy` / `--params-json`） |
| `scripts/rag_reset.py` | Milvus 清库重建 |
| `scripts/verify_rag_studio.py` | 工作台 live 验收 |
| `scripts/verify_chunk_strategies_live.py` | 五策略分块 + publish 门禁 Live |

---

## 4. 已归档 / 废弃

| 项 | 说明 |
|----|------|
| [archive/.../kb-eval-simplify-design.md](../archive/specs/2026-07-02-kb-eval-simplify-design.md) | 并入 eval-ui-redesign |
| per-scope Nacos publish、tenant merge | V2 改为 DB 版本化 |
| `docs/rag/golden-set.yaml` 运行时读取 | 改 MySQL `eval_suite_item` |
| 评测顶层三分栏、A/B eval、周报 Cron、`migrate_nacos_config_to_db.py` | **不做**，见 [backlog §已明确不做](./backlog.md) |

---

## 5. 配置与种子 SSOT

| 类型 | 位置 |
|------|------|
| 业务参数默认值 | `rag-service/src/main/resources/rag/defaults/config-seed.json` |
| DB 表结构 + 种子 | `docker/mysql/init/14-sunshine-rag-service.sql` |
| 基础设施 | `docs/nacos/sunshine-rag.yaml`（端口、存储、`rag.eval.suggest.system-prompt`） |
| 评测 suggest prompt | 同上 + sync 后重启 rag-service |

---

## 6. 相关非 RAG 阶段文档

| 文档 | 关联 |
|------|------|
| [phase3-production-hardening-design.md](../superpowers/specs/phase3-production-hardening-design.md) | §3.4 RAG 量化目标与检查门 |
| [phase4-platformization-design.md](../superpowers/specs/phase4-platformization-design.md) | §4.1–4.2 总览 |
| [superpowers/specs/README.md](../superpowers/specs/README.md) | 全项目四阶段索引 |
