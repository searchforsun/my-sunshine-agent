# RAG Backlog

> **Superseded（排期）**：阶段四 RAG **任务索引**见 [implementation-plan.md §4.1/4.2](../implementation-plan.md)；**设计与能力 SSOT** 见 [README.md](./README.md)。  
> **本文档定位**：**4.1 / 4.2 检查门留档**（2026-07-06 通过）+ 不阻塞关项的可选后续项。  
> **基准**：2026-07-06 代码库

---

## 检查门结论（2026-07-06）

| 范围 | 结论 | 依据 |
|------|------|------|
| **4.1 知识库工作台** | **通过** | G1–G5 ✅；G6 ⚠️ WARN（阶段三遗留，不挡关项） |
| **4.2 OCR / 多格式入库 L1** | **通过** | I1–I9 ✅；I10 `.doc` 与独立入库 Tab 明确不做 |

**Live 验收**：`python3 scripts/verify_rag_studio.py --skip-eval` 全绿（日志 `/tmp/verify_rag_studio_2026-07-06-full.log`）。

**运维补充（2026-07-06）**：`EvalJobRecoveryRunner` — 服务重启后自动恢复 `pending`/`running` 评测任务。

---

## 4.1 / 4.2 检查项明细

### 4.1 知识库工作台

| # | 检查项 | 状态 | 验收方式 |
|---|--------|:----:|----------|
| G1 | `/knowledge` 四 Tab 可用（文档/调试/配置/评测） | ✅ | 手动 + `verify_rag_studio.py` |
| G2 | 多 kb + 每 kb 配置版本链 draft→评测→active | ✅ | 配置 Tab 走通 publish |
| G3 | Chat 底栏 kb 选择器 + 会话 `kbId` 透传检索 | ✅ | Chat 选库 → RAG 命中对应库 |
| G4 | 评测运行 + 报告抽屉 + Suggest + eval_failed→draft | ✅ | 评测 Tab 跑标准回归集 |
| G5 | Nacos 业务参数 publish 已废弃 | ✅ | 无 `NacosPublishService`；配置走 MySQL |
| G6 | v6 相对 vector Recall@5 **+15%** | ⚠️ WARN | `rag_eval.py` 双轨报告 |

**4.1 结论**：**检查门通过**（G6 单列 WARN，建议后续「调参冲刺」）。

### 4.2 OCR / 多格式入库 L1

| # | 检查项 | 状态 | 验收方式 |
|---|--------|:----:|----------|
| I1 | PDF DashScope OCR + 异步解析 + 进度 | ✅ | 上传 PDF → 轮询 → 预览 |
| I2 | Word `.docx` 段落解析 + 异步 + 发布 | ✅ | 纯段落 docx 端到端 |
| I3 | 手动「发布生效」入库 Milvus/ES | ✅ | 草稿 ≠ active chunk |
| I4 | 与 MD 相同 `chunkMaxSize` 语义分块 | ✅ | 发布日志 + chunk 预览 |
| I5 | 无「第 N 页」等无业务标记 | ✅ | 重新上传发布后 chunk 无页码 |
| I6 | Word **表格**正文抽取 | ✅ | POI `XWPFTable` → Markdown 表格 |
| I7 | 入库前脱敏（desensitize :8600） | ✅ | `publishVersion` / `ingestText` 发布前 `DesensitizeClient` |
| I8 | quarantine 低置信度 + 人工确认 | ✅ | `preview` / `quarantine` 状态 + 文档 Tab「确认解析内容」 |
| I9 | ingest 状态机（spec §6.2 子集） | ✅ | `queued→parsing→preview|quarantine→active`（无独立入库 Tab） |
| I10 | 旧版 `.doc` | ⬜ 不做 | 仅 `.docx` |
| — | **独立入库 Tab `KbIngestPanel`** | ❌ 不做 | 用户确认；合并在文档 Tab |

**4.2 结论**：**检查门通过**（I10 / 独立入库 Tab 为范围外，不纳入关项）。

---

## 后续项（不阻塞 4.1/4.2 结案）

### 质量 / 文档（可选）

| 优先级 | 任务 | 估时 | 状态 |
|:------:|------|:----:|:----:|
| P1 | v6 +15% 调参冲刺 | 2–4d | ⬜ WARN 入账 |
| P2 | 已发布 PDF/Word 迁移指引 | 0.5d | ⬜ |
| P2 | 文档级多策略分块 Live（`verify_chunk_strategies_live.py`） | 0.5d | ✅ 2026-07-21 |

### 4.2 已关（归档）

| 优先级 | 任务 | 估时 | 状态 |
|:------:|------|:----:|:----:|
| P0 | live 验收留档 | 0.5d | ✅ 2026-07-06 |
| P0 | 删除 legacy 上传 API + KbConfigOverride 栈 | 0.5d | ✅ 2026-07-06 |
| P0 | Word 表格抽取 | 1.5d | ✅ |
| P1 | 发布前脱敏 | 1d | ✅ |
| P1 | OCR quarantine + 确认 | 2–3d | ✅ |
| P2 | ingest 状态机 | 2d | ✅ |
| P3 | 独立入库 Tab | 1d | ❌ 不做 |

### 工程化（按需）

| 优先级 | 任务 | 估时 | 状态 |
|:------:|------|:----:|------|
| P2 | `rag_reindex.py` 增强（进度、按 doc 重 embed） | 1d | 脚本已存在，可增强 |
| P3 | `KnowledgeRetrievalService` 内联删层直调 `RagClient` | 0.5d | ✅ 2026-07-06 |
| — | `DefaultKbResolver` 默认库 | ✅ | 已实现 |
| — | Chat `kbId` 全链路 | ✅ | 已实现 |

---

## 已明确不做 / 已废弃（勿再排期）

| 项 | 处置 |
|----|------|
| 评测顶层三分栏（独立「评测记录」Tab） | 2 Tab + 记录内嵌 + 抽屉 |
| `migrate_nacos_config_to_db.py` | 种子走 `14-sunshine-rag-service.sql` |
| 策略 A/B `POST /eval/ab`、评测周报 Cron | 不做 |
| per-scope Nacos publish、Badcase 独立表 | 已废弃 |
| Chat `#kb` 语法、本阶段 RBAC | 刻意不做 |

---

## 已实现（2026-07-06，以代码为准）

**Pipeline / 工作台（T0–T28）**

- `KnowledgeRetrievalPipeline` + ADR-002；`EffectiveConfigResolver` + V3 生命周期
- 四 Tab + `KbWorkbenchContext`；配置 schema / draft / publish 门禁
- `EvaluateService` + `EvalJobRecoveryRunner`（评测中断恢复）
- `scripts/rag_eval.py`、`verify_rag_studio.py`、`rag_reindex.py`、`rag_ingest_bulk.py`

**Chat 绑库**

- `ChatView` 底栏 `KbSelector`（模式左、知识库右）
- `useKbPreference` + 会话 `kbId` 持久化
- orchestrator `ChatStreamContextFactory` + `DefaultKbResolver` + `RagClient.searchKnowledge`

**文档入库（4.2 子集）**

- `DocumentParseJobService` 异步 PDF/Word；`DashScopeOcrService`、`DocxDocumentParser`
- 手动发布 → `markdownParser` + Milvus/ES；MinIO 草稿存储
- 前端 `KbDocPanel` 轮询进度、发布生效、chunk 预览
- 新建 kb 自动 `provisionBundleForNewKb`（`config-seed.json` → v1 active）

**验收留档（2026-07-06）**

```bash
python3 scripts/verify_rag_studio.py --skip-eval   # 单测 + Live 全绿
# 日志：/tmp/verify_rag_studio_2026-07-06-full.log
```

---

## 历史实施顺序（已执行完毕）

```
4.1 收尾  live 验收 + 删 deprecated API  ✅
4.2 L1    表格 / 脱敏 / quarantine / 状态机  ✅
可选      v6 调参 / reindex / 迁移说明      ⬜
```

---

## P4 — 阶段四远期（非 4.1/4.2）

| # | 功能 | 状态 |
|---|------|:----:|
| 12 | 文档理解 L2（版面/quarantine 全量） | ⬜ |
| 13 | 多模态 L3（Vision） | ⬜ |
| 14 | RBAC 细分 | 刻意不做 |
