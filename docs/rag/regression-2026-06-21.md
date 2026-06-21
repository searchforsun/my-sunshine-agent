# RAG 回归门禁 — 2026-06-21（Closure 验收）

> 本地 `RAG_URL=http://localhost:8400` · 方案 B（无远程 Grafana）

## 汇总

| 步骤 | 结果 |
|------|------|
| rag_reset + ingest（11 篇） | OK |
| v5 hybrid+rerank `--ci --fail-if-recall5-below 0.98` | **PASS** |
| v6 hybrid+rerank 生产门禁 | **PASS** |
| v6 vs vector 提升轨 `--compare-vector-json` | **WARN**（见下） |
| `mvn test -pl rag-service` | **PASS** |
| orchestrator QueryRewrite 单测 | **PASS**（`QueryRewriteServiceTest` 等） |
| `phase2_agent_demo.py --suite react` | SKIP（需 ecs4c16g 全链路） |

**Query 改写（3.8.1）**：`agent.rewrite.{rag,intent,empty-recall}` 默认开启；orchestrator 日志 `[QueryRewrite]`。

## v5 — hybrid+rerank

- run_tag: `20260621-142022-closure`
- Recall@5: **1.0** · MRR: **0.9467** · 负例 Empty: **1.0** · P95: **460.6 ms**
- **门禁: PASS**

## v6 — vector 基线

- run_tag: `20260621-142104-closure-v6-vector`
- Recall@5: **0.9762** · MRR: **0.9274** · P95: **190.4 ms**
- 漏召：`q_adv_009`（报一下上个月差旅）、`q_adv_010`（餐费发票）、`q_adv_011`（招待费标准）

## v6 — hybrid+rerank

- run_tag: `20260621-142111-closure-v6`
- Recall@5: **1.0** · MRR: **0.9841** · 负例 Empty: **1.0** · P95: **340.9 ms**
- **生产门禁: PASS**
- vs vector：recall@5 +2.4pp（rel 2.4%），mrr +5.7pp（rel 6.1%）
- **提升轨: WARN** — vector 基线已 97.6%，hybrid 满分后相对提升不足 5pp/10%

## 复现命令

```bash
set RAG_URL=http://localhost:8400
python scripts/rag_reset.py --rag-url http://localhost:8400
python scripts/rag_ingest_bulk.py --rag-url http://localhost:8400
python scripts/rag_eval.py --suite v5 --strategy hybrid+rerank --ci --fail-if-recall5-below 0.98 --tag closure
python scripts/rag_eval.py --suite v6 --strategy vector --tag closure-v6-vector
python scripts/rag_eval.py --suite v6 --strategy hybrid+rerank --ci --compare-vector-json docs/rag/reports/baseline-*-closure-v6-vector.json --tag closure-v6
mvn test -pl rag-service
```
