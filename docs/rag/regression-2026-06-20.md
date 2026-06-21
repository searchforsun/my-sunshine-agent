# RAG 回归门禁 — 2026-06-20

> 本地开发（方案 B）：RAG 在本机 `:8400`，**不用**远程 Grafana；`RAG_URL=http://localhost:8400`。  
> CI / 服务器联调可配置 `RAG_URL=http://ecs4c16g:8400`（需 rag 部署在 ecs4c16g）。

## 基线验收（本地 :8400，hybrid+rerank + 向量锚点）

| 套件 | Recall@5 | MRR | 正例 Empty | 负例 Empty | P95 (ms) | 门禁 |
|------|----------|-----|------------|------------|----------|------|
| v5 | 1.0000 | 0.9467 | 0.0 | 1.0 | 382 | **PASS** |
| v6 | 1.0000 | 0.9841 | 0.0 | 1.0 | 431 | **PASS** |

报告：`baseline-20260620-173721.json`（v5）、`baseline-20260620-173705.json`（v6）

## 本地 CI 门禁命令

```bash
set RAG_URL=http://localhost:8400
pip install -r scripts/requirements.txt

# 单测（无需 RAG）
python scripts/test_rag_eval_gates.py -v

# v5 回归轨
python scripts/rag_eval.py --suite v5 --strategy hybrid+rerank --ci --fail-if-recall5-below 0.98

# v6 提升轨（先 vector 基线，再对比）
python scripts/rag_eval.py --suite v6 --strategy vector --tag local-v6-vector
python scripts/rag_eval.py --suite v6 --strategy hybrid+rerank --ci --fail-if-recall5-below 0.98 ^
  --compare-vector-json docs/rag/reports/baseline-<tag>-local-v6-vector.json
```

## CI Workflow

- 文件：`.github/workflows/rag-eval.yml`
- `gate-logic` job：每次 push 跑门禁单测
- `rag-regression` job：配置 `RAG_URL` secret 或 `workflow_dispatch` 时跑 v5/v6 全链路评测
