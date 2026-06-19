# 阶段三：生产加固 — 实施计划

> **For agentic workers:** 优先执行 **RAG 任务 R1–R8**；每完成一个 RAG 子阶段必须跑 `rag_eval.py` 并更新 `docs/rag/baseline-report.md`。

**Goal:** 生产级加固 — **RAG 评测驱动的召回优化**为主轴，叠加多租户、HITL、Planner、Grafana、审计扩展。

**Architecture:** `rag-service` 内聚合检索策略；评测脚本独立；orchestrator 仅消费稳定 `RetrievalService` API。

**Spec:** `docs/superpowers/specs/2026-06-19-phase3-production-hardening-design.md`

**前置:** 阶段二 `phase2-closure-plan.md` 检查门通过

---

## 排期总览

```
周 1–2  RAG 评测基建 + 入库 metadata + ES BM25
周 3–4  Hybrid RRF + BGE-Reranker + 扫参回归
周 4–5  多租户 Milvus + HITL + Grounding
周 5–6  Planner/Executor + Grafana + Tool Audit
```

---

## RAG 任务（优先）

### Task R1: 评测脚本与基线报告

**Files:**
- Create: `scripts/rag_eval.py`
- Create: `docs/rag/baseline-report.md`
- Use: `docs/rag/golden-set.yaml`

- [ ] **R1.1** `rag_eval.py` 读取 golden-set，调用 `POST /api/retrieval/search`
- [ ] **R1.2** 输出 Recall@3/5/10、MRR、EmptyRate、latency CSV
- [ ] **R1.3** 跑通 vector 基线，填写 `baseline-report.md`
- [ ] **R1.4** `mvn test -pl rag-service` 保持绿

**验收:** `python scripts/rag_eval.py` 退出码 0，报告含实测数字

---

### Task R2: 入库 metadata 与 ES 索引

**Files:**
- Modify: `rag-service/.../IngestionController.java`
- Modify: `rag-service/.../MilvusService.java`
- Create: `rag-service/.../ElasticsearchIndexService.java`
- Modify: `docs/nacos/sunshine-rag.yaml`

- [ ] **R2.1** Milvus schema 增加 `tenant_id`, `section_path`, `chunk_index`
- [ ] **R2.2** 入库双写 ES（content + metadata）
- [ ] **R2.3** Flyway 或启动迁移脚本处理旧 collection 重建
- [ ] **R2.4** 单测：入库后 ES + Milvus 均可查到

**验收:** 上传 `公司请假流程规范.md` 后 ES `_count` > 0

---

### Task R3: BM25 稀疏检索

**Files:**
- Create: `rag-service/.../Bm25SearchService.java`

- [ ] **R3.1** ES `match` 查询，返回 doc_id + score
- [ ] **R3.2** 与 Milvus 结果统一为 `RetrievalCandidate` record
- [ ] **R3.3** 单测：专有名词 query BM25 排名优于纯向量（mock）

**验收:** 单测绿 + 日志可见 bm25 topK

---

### Task R4: Hybrid RRF 融合

**Files:**
- Create: `rag-service/.../HybridRetrievalService.java`
- Modify: `rag-service/.../RetrievalService.java`

- [ ] **R4.1** 实现 RRF：`score = Σ 1/(k + rank)`
- [ ] **R4.2** Nacos `rag.search.strategy` 切换 vector/hybrid
- [ ] **R4.3** `rag_eval.py --strategy hybrid` 对比基线

**验收:** Recall@5 不低于 vector 基线（混合应提升）

---

### Task R5: BGE-Reranker 精排

**Files:**
- Create: `rag-service/.../RerankService.java`
- Modify: `docs/nacos/sunshine-rag.yaml`

- [ ] **R5.1** 接入 Rerank API（或本地模型）
- [ ] **R5.2** 融合 top-20 → rerank → top-5
- [ ] **R5.3** `min-score` 改为 rerank 分阈值
- [ ] **R5.4** `rag_eval.py` 记录 hybrid vs hybrid+rerank 报告

**验收:** Recall@5 较 R4 提升 ≥5% 或 MRR 提升 ≥8%

---

### Task R6: RAG Metrics + Grafana 面板

**Files:**
- Modify: `rag-service` — Micrometer metrics
- Create: `docs/grafana/rag-dashboard.json`（或 prometheus 规则片段）

- [ ] **R6.1** 暴露 `rag_search_duration_seconds`, `rag_empty_total`, `rag_hits_total`
- [ ] **R6.2** Grafana 导入 RAG 面板
- [ ] **R6.3** 4 条告警规则（spec 3.5）

**验收:** 压测 10 次检索后面板有数据

---

### Task R7: Query Rewrite（可选）

- [ ] **R7.1** 仅当 vector+hybrid 均为 empty 时触发
- [ ] **R7.2** LLM 生成 1 个改写 query 再检索
- [ ] **R7.3** 评测 EmptyRate 是否下降，记录 Token 成本

**验收:** 负例 query 不触发 rewrite；正例 EmptyRate 下降

---

### Task R8: CI 回归门禁

**Files:**
- Modify: `scripts/rag_eval.py`
- Create: `.github/workflows/rag-eval.yml`（或文档说明本地门禁）

- [ ] **R8.1** `--fail-if-recall5-below` 参数
- [ ] **R8.2** 记录 regression 报告 `docs/rag/regression-YYYY-MM-DD.md`

**验收:** 故意降低 minScore 时脚本非零退出

---

## 其他任务

### Task 3.2: 多租户 Milvus Partition

- [ ] **3.2.1** 检索/入库带 `tenantId`（来自 `x-tenant-id` 或默认 `default`）
- [ ] **3.2.2** 集成测试：跨租户 0 命中

### Task 3.3: HITL 写操作确认

- [ ] **3.3.1** Catalog `sideEffect` 字段
- [ ] **3.3.2** `PreToolCallHook` + Redis 暂停
- [ ] **3.3.3** 前端确认 UI + `POST /api/chat/confirm-tool`

### Task 3.1: Planner / Executor

- [ ] **3.1.1** Nacos planner prompt + 工具白名单
- [ ] **3.1.2** `PlannerExecutor` 新模式或 react 子流程
- [ ] **3.1.3** Timeline `plan` 步骤展示

### Task 3.6: Tool Audit 扩展

- [ ] **3.6.1** `sunshine-tool-audit` topic
- [ ] **3.6.2** ES 索引 + 查询 API

### Task 3.7: Grounding 校验

- [ ] **3.7.1** `AnswerGroundingChecker` 规则 MVP
- [ ] **3.7.2** 集成测试：无 tool 调用时拦截编造财务数据

---

## Spec Coverage Self-Review

- [ ] RAG 全链路：评测 → 混合 → Rerank → 指标 → CI
- [ ] orchestrator 对 RAG API 无破坏性变更
- [ ] 阶段三检查门与 spec 第七章一一对应
