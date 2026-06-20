# RAG 评测与语料运维 SOP

> 阶段二收尾 SSOT · 详设：`docs/superpowers/specs/2026-06-20-phase2-closure-design.md`

## 前置

- ecs4c16g 上 `rag-service :8400` 已启动  
- `docs/nacos/sunshine-rag.yaml` 已 sync（`min-score` 等）  
- 语料 Markdown 在 `docs/knowledge/`  

## 一键流程（推荐）

```bash
# 1. 清空向量库并重建 collection
python scripts/rag_reset.py

# 2. 按 golden-set.corpus 批量入库
python scripts/rag_ingest_bulk.py

# 3. 跑评测、写报告、校验生产门禁（每次生成带时间戳的新文件，不覆盖历史）
python scripts/rag_eval.py --report-md --gate
# 可选附加标记：python scripts/rag_eval.py --report-md --tag v5-smoke
```

环境变量：

| 变量 | 默认 |
|------|------|
| `RAG_URL` | `http://ecs4c16g:8400` |

## 文件说明

| 文件 | 用途 |
|------|------|
| `golden-set.yaml` | 语料清单 + 123 标注 query + `eval.production_gates` |
| `golden-set-schema.md` | 标注规范 |
| `baseline-report.md` | 阶段二基线实测（人工摘要 + 迭代记录） |
| `reports/baseline-*.json` | `rag_eval` 机器报告（`baseline-YYYYMMDD-HHMMSS.json`） |
| `reports/rag-eval-report-*.md` | `rag_eval --report-md` Markdown 报告（同时间戳配对） |

## 与 Live 验收关系

`phase2_agent_demo.py --suite workflow` 的 knowledge-qa 用例使用 golden-set 中的**高置信 query**，跑 demo 前应先完成 ingest。

## 阶段三

基线报告 + `rag_eval.py` 作为 hybrid/rerank 优化前后的**回归对比**输入。  
双轨门禁：v5 不退化 + v6 adversarial 提升 ≥15%。详见 `superpowers/specs/2026-06-19-phase3-production-hardening-design.md`。
