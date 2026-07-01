# RAG 回归门禁 — 2026-07-01

> 由 `rag_eval.py --regression-md` 或 CI `rag-eval` workflow 生成。

## 2026-07-01T11:47:30 — v5 / hybrid+rerank

- run_tag: `20260701-114730`
- queries: 123
- Recall@5: 0.9821 · MRR: 0.9435
- 正例 Empty: 0.0 · 负例 Empty: 0.3636
- P95: 7942.3 ms
- **门禁: FAIL**
- FAIL: negative empty_rate 0.3636 < 0.95
- FAIL: p95 latency 7942.3ms > 500ms

## 2026-07-01T15:28:34 — v5 / hybrid+rerank

- run_tag: `20260701-152834`
- queries: 123
- Recall@5: 0.9821 · MRR: 0.9375
- 正例 Empty: 0.0 · 负例 Empty: 0.3636
- P95: 452.2 ms
- **门禁: FAIL**
- FAIL: negative empty_rate 0.3636 < 0.95

