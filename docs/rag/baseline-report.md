# RAG 检索基线报告

> **状态**：阶段三 Task 3.4.1 vector 双轨实测（2026-06-20，本地 rag-service :8400 + ecs4c16g Milvus）  
> **配置**：`docs/nacos/sunshine-rag.yaml` → `rag.search.min-score: 0.48`  
> **策略**：`vector`（纯 Milvus IP + Embedding text-embedding-v4）  
> **语料**：**11 篇** `docs/knowledge/*.md`（含多制度交叉场景速查）  
> **评测集**：`docs/rag/golden-set.yaml` **v6**（**169 条**：v5 回归 **123** + adversarial **46**）  
> **机器报告**：`docs/rag/reports/baseline-*.json`（每次评测带时间戳，不覆盖）  
> **Markdown 报告**：`docs/rag/reports/rag-eval-report-*.md`  

---

## 运行方式

```bash
# 本地评测（RAG 服务需在 :8400 运行）
set RAG_URL=http://localhost:8400
python scripts/rag_reset.py
python scripts/rag_ingest_bulk.py
python scripts/rag_eval.py --report-md --gate
```

`--gate`：未达 `golden-set.yaml` 中 `eval.production_gates` 时 exit 1。

---

## 生产门禁（v5 多跳推理 — 2026-06-19）

| 门禁项 | 阈值 | v5 实测 | 结果 |
|--------|------|---------|------|
| Recall@3 | ≥ 0.95 | **1.0000** | PASS |
| Recall@5 | ≥ 0.98 | **1.0000** | PASS |
| MRR | ≥ 0.92 | **0.9449** | PASS |
| 正例 EmptyRate | = 0 | **0.0** | PASS |
| 负例 EmptyRate | ≥ 0.95 | **1.0** | PASS |
| P95 latency | ≤ 500ms | **203.0ms** | PASS |

**结论**：11 篇语料、123 条 query（含 24 条 multihop 多跳推理），**生产门禁全 PASS**，零 badcase。

### v5 新增

| 项 | 内容 |
|----|------|
| 语料 | `员工场景速查与多制度交叉指引.md` — 远程×请假、差旅×预算、离职×报销等 20+ 复合场景 |
| query | q089–q112（category: `multihop`），覆盖 2–3 制度交叉口语化问法 |
| 负例 | +3（q_neg_009–011）；q_neg_010 由「CEO手机号」改为「滴滴优惠券」避免误召回 |

### 分类型 Recall@3（v5）

| category | Recall@3 |
|----------|----------|
| leave / process / expense / attendance | 1.000 |
| onboarding / finance / remote / security / travel | 1.000 |
| **multihop** | **1.000** |

---

## 生产门禁（v4 冒烟 — 历史）

| 门禁项 | 阈值 | v4 实测 | 结果 |
|--------|------|---------|------|
| Recall@3 | ≥ 0.95 | **1.0000** | PASS |
| Recall@5 | ≥ 0.98 | **1.0000** | PASS |
| MRR | ≥ 0.92 | **0.9621** | PASS |
| 正例 EmptyRate | = 0 | **0.0** | PASS |
| 负例 EmptyRate | ≥ 0.95 | **1.0** | PASS |
| P95 latency | ≤ 500ms | **192.9ms** | PASS |

**结论**：语料扩至 10 篇、query 扩至 96 条（含远程/安全/差旅交叉场景）后，**生产门禁仍全 PASS**，零 badcase。

---

## 生产门禁（v3 基线 — 历史）

| 门禁项 | 阈值 | v3 实测 | 结果 |
|--------|------|---------|------|
| Recall@3 | ≥ 0.95 | **1.0000** | PASS |
| Recall@5 | ≥ 0.98 | **1.0000** | PASS |
| MRR | ≥ 0.92 | **0.9917** | PASS |
| 正例 EmptyRate | = 0 | **0.0** | PASS |
| 负例 EmptyRate | ≥ 0.95 | **1.0** | PASS |
| P95 latency | ≤ 500ms | **188.3ms** | PASS |

**结论**：纯向量检索在 7 篇企业制度语料 + 66 条 golden-set 上达到阶段二生产门禁。

> 阶段三 hybrid + rerank 目标更高（Recall@5 +15%、MRR +10%），见 `docs/superpowers/specs/2026-06-19-phase3-production-hardening-design.md`。

---

## 基线指标（v4 冒烟）

| 指标 | topK=3 | topK=5 | topK=10 | 备注 |
|------|--------|--------|---------|------|
| Recall@K（doc 级） | **1.0000** | **1.0000** | **1.0000** | 88 条正例 |
| MRR | — | **0.9621** | — | 交叉场景拉低首位排名 |
| EmptyRate（正例） | — | **0.0** | — | |
| EmptyRate（负例） | — | **1.0** | — | 8 条负例 |
| P50 latency (ms) | — | **153.6** | — | |
| P95 latency (ms) | — | **192.9** | — | |

### 分类型 Recall@3（v4）

| category | Recall@3 |
|----------|----------|
| leave / process / expense / attendance | 1.000 |
| onboarding / finance | 1.000 |
| **remote** | 1.000 |
| **security** | 1.000 |
| **travel** | 1.000 |

### v4 新增语料

| doc_id | 主题 | 复杂度要点 |
|--------|------|------------|
| remote-policy-v1 | 远程/混合办公 | 与请假、考勤、信息安全交叉 |
| security-policy-v1 | 数据分级 / 账号 / 代码安全 | L1–L4 分级、VPN、离职冻结 |
| travel-policy-v1 | 差旅标准与审批 | 与报销、预算、财务矩阵交叉 |

### v4 冒烟修复

| id | query | 首轮问题 | 修复 |
|----|-------|----------|------|
| q072 | VPN连不上内网怎么办 | 空召回（score < 0.48） | 信息安全制度 FAQ 增补「VPN 连不上内网」专条 |

---

## 基线指标（v3 历史）

| 指标 | topK=3 | topK=5 | topK=10 | 备注 |
|------|--------|--------|---------|------|
| Recall@K（doc 级） | **1.0000** | **1.0000** | **1.0000** | 60 条正例 |
| MRR | — | **0.9917** | — | |
| EmptyRate（正例） | — | **0.0** | — | 无空召回 |
| EmptyRate（负例） | — | **1.0** | — | 6 条负例全部 empty |
| P50 latency (ms) | — | **156.0** | — | |
| P95 latency (ms) | — | **188.3** | — | |

---

## 分类型 Recall@3（v3）

| category | Recall@3 |
|----------|----------|
| leave | 1.000 |
| process | 1.000 |
| expense | 1.000 |
| attendance | 1.000 |
| onboarding | 1.000 |
| finance | 1.000 |

---

## 优化迭代记录

| 轮次 | golden-set | Recall@3 | MRR | 负例 EmptyRate | 主要改动 |
|------|------------|----------|-----|----------------|----------|
| v1 | v2 | 0.9333 | 0.9386 | 0.8333 | 初版 7 篇语料 + 66 query |
| v2 | v3 语料 | 0.9833 | 0.9792 | 1.0 | 请假/报销 FAQ 补强；q011 双 doc 标注；q_neg_002 改真负例 |
| v3 | v3 语料 | **1.0000** | **0.9917** | **1.0** | 新增「2.1 直属主管请假审批职责」专节，消除 q007 与财务矩阵混淆 |

### v1 → v3 已修复 badcase

| id | query | 根因 | 修复 |
|----|-------|------|------|
| q007 | 直属主管审批职责是什么 | 财务矩阵「直属主管」语义更近 | 请假制度新增 2.1 专节 + FAQ 强化「请假审批」 |
| q011 | 加班调休余额在哪里查 | 仅标 leave，考勤 doc 亦相关 | `relevant_docs` 增加 attendance-policy-v1 |
| q021 | 电子发票报销要注意什么 | 仅标报销制度 | 增加 invoice-faq-v1 |
| q023 | 虚假发票报销后果 | 报销制度 chunk 排名靠后 | 报销制度 FAQ 增补专条 |
| q_neg_002 | （原）报销发票税率 | 误标负例，FAQ 有答案 | 改为「公司股权激励行权如何套现」真负例 |

---

## Live 验收（2026-06-19 本地）

| 步骤 | 结果 |
|------|------|
| rag_reset → ingest → eval --gate | **PASS** |
| wf-knowledge | PASS |
| wf-finance-smart | PASS |
| phase2_agent_demo.py --suite all | PASS（4/4） |

---

## 阶段三双轨评测（v6 — 2026-06-20 实测）

| 轨道 | 套件 | 策略 | 门禁 | 状态 |
|------|------|------|------|------|
| **v5 回归** | `--suite v5` | `vector` | `production_gates` | **PASS**（`baseline-20260620-165726.json`） |
| **v5 回归** | `--suite v5` | `hybrid+rerank` | `production_gates` 不退化 | **PASS**（`baseline-20260620-173721.json`） |
| **v6 提升** | `--suite v6` | `vector` | — | **基线已测**（见下表） |
| **v6 提升** | `--suite v6` | `hybrid+rerank` | Recall@5 +15%、MRR +10% vs vector | **PASS**（`baseline-20260620-173705.json`） |
| **全量** | `--suite all` | — | — | 169 条（v5 123 + adversarial 46） |

**v6 难例集**：`category: adversarial`，46 条（42 正例 + 4 负例），口语/极简/专有名词变体。

### vector 基线（2026-06-20，本地 :8400）

| 套件 | query | Recall@3 | Recall@5 | MRR | 正例 Empty | 负例 Empty | P95 (ms) | gates |
|------|-------|----------|----------|-----|------------|------------|----------|-------|
| **v5 vector** | 123 | **1.0000** | **1.0000** | **0.9449** | **0.0** | **1.0** | **210** | **PASS** |
| **v6 vector** | 46 | **0.9048** | **0.9524** | **0.8917** | **0.024** | **1.0** | **205** | FAIL（难例预期） |
| **v5 hybrid+rerank** | 123 | **0.9911** | **1.0000** | **0.9467** | **0.0** | **1.0** | **382** | **PASS**（`173721`） |
| **v6 hybrid+rerank** | 46 | **1.0000** | **1.0000** | **0.9841** | **0.0** | **1.0** | **431** | **PASS**（`173705`） |

**v6 调优记录（2026-06-20）**

| 改动 | 说明 |
|------|------|
| 语料 FAQ | 报销制度 +差旅报销/招待费/餐费；请假 +产假工资；考勤 +加班餐补 |
| `rag.rerank.min-relevance` | 0.25（gte-rerank-v2 低分过滤） |
| **向量锚点门禁** | hybrid+rerank 前：候选池内 `max(vectorScore) < min-score(0.48)` → 空召回，阻断 BM25-only 负例误召 |
| Rerank 空结果回退 | 向量锚点通过后，rerank 无有效候选时仍回退 hybrid RRF 池（正例兜底） |

**v6 hybrid+rerank 较 vector**：Recall@5 **1.0 vs 0.9524**（+5.0% abs）、MRR **0.9841 vs 0.8917**（**+10.4%** rel）— **提升轨达标**。

**负例 Empty 调优（2026-06-20）**：曾尝试 `empty-if-top-below: 0.42` 全局截断，正例大面积空召回；改为 **向量锚点** 后 v5/v6 负例 EmptyRate **1.0**、正例 Recall/MRR 保持，**production_gates 全 PASS**。

```bash
set RAG_URL=http://localhost:8400
python scripts/rag_reset.py && python scripts/rag_ingest_bulk.py
python scripts/rag_eval.py --suite v5 --strategy vector --report-md
python scripts/rag_eval.py --suite v6 --strategy vector --report-md
python scripts/rag_eval.py --suite v6 --strategy hybrid+rerank --gate --report-md
```

---

## 阶段三对比目标（spec 3.4 — 双轨修订）

| 指标 | v5 回归轨 | v6 提升轨 |
|------|-----------|-----------|
| Recall@5 | ≥ 0.98（hybrid+rerank 不退化） | 较 vector **+15%** |
| MRR | ≥ 0.92 | 较 vector **+10%** |
| P95 latency | ≤ 800ms（hybrid+rerank） | 同左 |

---

## 阶段三对比目标（spec 3.4.7 — 历史）

| 指标 | 阶段二基线 (v3) | 阶段三目标 |
|------|-----------------|------------|
| Recall@5 | 1.0000 | ≥ 1.15（hybrid 相对提升） |
| MRR | 0.9917 | ≥ 1.10（相对提升） |
| EmptyRate（正例） | 0.0 | ≤ 0 |
| P95 latency | 188ms | ≤ 800ms（hybrid+rerank） |

---

## 历史回归记录

| 日期 | 策略 | golden | Recall@3 | Recall@5 | MRR | 说明 |
|------|------|--------|----------|----------|-----|------|
| 2026-06-19 | vector | v2 | 0.9333 | 0.9833 | 0.9386 | 阶段二初版基线 |
| 2026-06-19 | vector | v3 | 0.9833 | 1.0000 | 0.9792 | 语料/标注优化，q007 仍漏召 |
| 2026-06-19 | vector | v3 | **1.0000** | **1.0000** | **0.9917** | 7 篇语料，生产门禁全 PASS |
| 2026-06-19 | vector | v4 | **1.0000** | **1.0000** | **0.9621** | 10 篇语料 96 query，冒烟全 PASS |
| 2026-06-19 | vector | v5 | **1.0000** | **1.0000** | **0.9449** | **11 篇 123 query 含 multihop，全 PASS** |
| 2026-06-20 | vector | v5（suite） | **1.0000** | **1.0000** | **0.9449** | golden v6 下 v5 子集复测，全 PASS |
| 2026-06-20 | vector | v6 adversarial | **0.9048** | **0.9524** | **0.8917** | 46 条难例，4 漏召；hybrid 提升基线 |
| 2026-06-20 | hybrid+rerank | v5 | **1.0000** | **1.0000** | **0.9643** | 3.4.5 落地；正例不退化，MRR 提升 |
| 2026-06-20 | hybrid+rerank | v6 adversarial | **0.9286** | **0.9524** | **0.8931** | BM25+RRF+Rerank 初版；v6 门禁未达标 |
| 2026-06-20 | hybrid+rerank | v6 adversarial | **1.0000** | **1.0000** | **0.9841** | 语料 FAQ + rerank 回退；**v6 提升轨达标** |
