# RAG 评测报告 — 2026-06-21T14:21:11

> golden-set v6 · suite=v6 · strategy=hybrid+rerank · 46 queries · min_score=0.48 · run=20260621-142111-closure-v6

## 汇总指标

| 指标 | 值 | 生产门禁 | 结果 |
|------|-----|----------|------|
| Recall@3 | 1.0 | ≥ 0.95 | PASS |
| Recall@5 | 1.0 | ≥ 0.98 | PASS |
| MRR | 0.9841 | ≥ 0.92 | — |
| 正例 EmptyRate | 0.0 | = 0 | — |
| 负例 EmptyRate | 1.0 | ≥ 0.95 | — |
| P50 延迟 (ms) | 303.0 | — | — |
| P95 延迟 (ms) | 340.9 | ≤ 500 | — |

**生产门禁：FAIL**
- v6 mrr improvement 0.0611 < 0.1 vs vector
- v6 recall@5 improvement rel=0.0244 abs=0.0238 (need rel>=0.15 or abs>=0.05) vs vector

## 分类型 Recall@3

| category | Recall@3 |
|----------|----------|
| adversarial | 1.0 |

## Badcase（Recall@3 未命中）

无。

## 负例误召回

无。
