# RAG 评测报告 — 2026-06-21T14:20:22

> golden-set v6 · suite=v5 · strategy=hybrid+rerank · 123 queries · min_score=0.48 · run=20260621-142022-closure

## 汇总指标

| 指标 | 值 | 生产门禁 | 结果 |
|------|-----|----------|------|
| Recall@3 | 0.9911 | ≥ 0.95 | PASS |
| Recall@5 | 1.0 | ≥ 0.98 | PASS |
| MRR | 0.9467 | ≥ 0.92 | PASS |
| 正例 EmptyRate | 0.0 | = 0 | — |
| 负例 EmptyRate | 1.0 | ≥ 0.95 | — |
| P50 延迟 (ms) | 313.4 | — | — |
| P95 延迟 (ms) | 460.6 | ≤ 500 | — |

**生产门禁：PASS**

## 分类型 Recall@3

| category | Recall@3 |
|----------|----------|
| attendance | 1.0 |
| expense | 1.0 |
| finance | 1.0 |
| leave | 1.0 |
| multihop | 1.0 |
| onboarding | 1.0 |
| process | 0.75 |
| remote | 1.0 |
| security | 1.0 |
| travel | 1.0 |

## Badcase（Recall@3 未命中）

| id | query | 期望 doc | Top3 命中 |
|----|-------|----------|-----------|
| q010 | 销假是什么意思怎么操作 | 公司请假流程规范 | 员工场景速查与多制度交叉指引(0.6458); 新员工入职指引(1.0); 员工场景速查与多制度交叉指引(0.6146) |

## 负例误召回

无。
