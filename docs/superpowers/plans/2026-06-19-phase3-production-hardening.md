# 阶段三：生产加固 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按 Task 逐步执行。  
> **覆盖度审计：** [2026-06-20-phased-implementation-coverage.md](./2026-06-20-phased-implementation-coverage.md)

**Goal:** RAG 双轨评测 + PLAN_WORKFLOW + 多租户 / HITL / 全链路可观测（17 条检查门）。

**Architecture:** `rag-service` 内聚合检索策略；`orchestrator` 负责编排/HITL/Plan；`skill-manager` 独立 Catalog；评测脚本 + golden-set 驱动回归。

**Tech Stack:** JDK 21, Spring Boot 3.2.9, Milvus, Elasticsearch, AgentScope-Java 1.0.7, Vue3/Naive UI, Python `rag_eval.py`

**SSOT 设计:** [phase3-production-hardening-design.md](../specs/phase3-production-hardening-design.md)  
**多 Agent / Skills:** [2026-06-19-multi-agent-architecture.md](./2026-06-19-multi-agent-architecture.md)（3.9–3.12）

**前置:** 阶段二 golden-set v5 基线全 PASS

---

## 排期总览

```
周 1    3.4.1 评测 + 3.10.1 AgentRuntime（并行，见 multi-agent plan）
周 2    3.4.2 ES 双写 + 3.10.2–3.10.3
周 3    3.4.3–3.4.4 Hybrid + 3.11 skill-manager
周 4    3.4.5 Rerank + 3.8.1 QueryRewrite + 3.5.1 Grafana
周 5    3.4.8 CI + 3.2 多租户 + 3.5.2 Sentinel/告警
周 6    3.4 验收 + 3.9 PLAN_WORKFLOW + 3.8.2 PromptComposer
周 7    3.3 HITL + 3.6 审计
周 8    3.7 Grounding + 3.12 前端 + 总检查门
```

---

## Task 3.4.1: golden-set v6 + rag_eval 双轨

**Files:**
- Modify: `scripts/rag_eval.py`
- Modify: `docs/rag/golden-set.yaml`
- Modify: `docs/rag/baseline-report.md`

- [ ] **Step 1:** 在 `golden-set.yaml` 增加 `adversarial` 分类 ≥40 条（口语变体、专有名词、跨制度 multihop）
- [ ] **Step 2:** `rag_eval.py` 增加 `--suite v5|v6|all` 与 `--strategy vector|hybrid|hybrid+rerank`
- [ ] **Step 3:** 跑 vector 基线并写入 `baseline-report.md`

```bash
set RAG_URL=http://localhost:8400
python scripts/rag_reset.py
python scripts/rag_ingest_bulk.py
python scripts/rag_eval.py --suite v5 --strategy vector --report-md
```

Expected: 退出码 0；报告含 Recall@5/MRR/P95

- [ ] **Step 4:** `mvn test -pl rag-service` 绿

---

## Task 3.4.2: 入库 metadata + ES 双写

**Files:**
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/MilvusService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/controller/IngestionController.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/service/ElasticsearchIndexService.java`
- Modify: `docs/nacos/sunshine-rag.yaml`
- Test: `rag-service/src/test/java/com/sunshine/rag/...`（新建 ES 双写单测）

- [ ] **Step 1:** Milvus schema 增加 `tenant_id`, `section_path`, `chunk_index`, 可空 `source_type`
- [ ] **Step 2:** 入库路径双写 ES（content + metadata）
- [ ] **Step 3:** 单测：mock ES 断言 index 调用
- [ ] **Step 4:** 验收：`POST` 上传后 ES `_count` > 0

---

## Task 3.4.3–3.4.5: BM25 + Hybrid + Rerank

**Files:**
- Create: `rag-service/.../Bm25SearchService.java`
- Create: `rag-service/.../HybridRetrievalService.java`
- Create: `rag-service/.../RerankService.java`
- Modify: `rag-service/.../RetrievalService.java`
- Modify: `docs/nacos/sunshine-rag.yaml`

- [ ] **3.4.3** ES match 查询 + `RetrievalCandidate` 统一模型 + 单测
- [ ] **3.4.4** RRF 融合 + `rag.search.strategy` 开关 + `rag_eval --strategy hybrid`
- [ ] **3.4.5** Rerank top-20→5 + v5/v6 对比报告

**验收:** v5 hybrid+rerank 不退化；v6 较 vector Recall@5 +15%

---

## Task 3.4.6–3.4.8: Metrics + CI

**Files:**
- Modify: `rag-service` — Micrometer `rag_search_*` 指标
- Create: `docs/grafana/rag-dashboard.json`（或 prometheus 规则片段）
- Modify: `scripts/rag_eval.py` — `--fail-if-recall5-below`
- Create: `.github/workflows/rag-eval.yml`（或文档说明本地门禁）

- [ ] **3.4.6** Grafana RAG 面板（与 Task 3.5 联动）
- [ ] **3.4.8** CI 门禁 + `docs/rag/regression-YYYY-MM-DD.md`

---

## Task 3.2: 多租户隔离

**Files:**
- Modify: `rag-service/.../RetrievalService.java`, `IngestionController.java`, `MilvusService.java`
- Modify: `orchestrator/.../memory/MemoryMessageBuilder.java` 或 `MtmService` — tenant 过滤
- Modify: `gateway/...` Sentinel 租户 QPS 规则
- Modify: `docs/nacos/sunshine-gateway.yaml`, `sunshine-rag.yaml`
- Test: `rag-service` 跨租户集成测试

- [ ] **3.2.1** 检索/入库带 `x-tenant-id`（默认 `default`）
- [ ] **3.2.2** MTM 向量召回同 tenant 过滤
- [ ] **3.2.3** 集成测试：租户 A 语料，租户 B 检索 0 命中

```bash
mvn test -pl rag-service -Dtest=*Tenant*
```

---

## Task 3.3: HITL 写操作确认

**Files:**
- Modify: `tool-manager/...` Catalog DTO — `sideEffect: read|write`
- Modify: `docs/nacos/sunshine-tools.yaml` — 写工具标记 `write`
- Create: `orchestrator/.../hook/PreToolCallConfirmationHook.java`
- Modify: `orchestrator/.../controller/ChatController.java` — `POST /api/chat/confirm-tool`
- Modify: `sunshine-ui/src/...` — 确认对话框 + SSE `type:confirmation` 处理
- Modify: `bff/...` — 透传 confirm-tool

- [ ] **3.3.1** Catalog `sideEffect` 字段 + 种子写工具（如未来 OA mock）
- [ ] **3.3.2** Hook 暂停 Agent + Redis 令牌；**子 Agent run 同样拦截**
- [ ] **3.3.3** 前端确认 → 恢复执行；拒绝/超时 → 步骤 SKIP

**验收:** 标记 `write` 的工具必须弹窗确认后才执行

---

## Task 3.5: Grafana + Sentinel

**Files:**
- Modify: `rag-service` metrics（见 3.4.6）
- Create/Modify: Prometheus 规则 + Grafana dashboard JSON
- Modify: `docs/nacos/sunshine-gateway.yaml` — Sentinel 租户 QPS

- [ ] **3.5.1** Grafana RAG 专区 6 面板有数据
- [ ] **3.5.2** 4 条告警可触发（测试环境）
- [ ] **3.5.3** Sentinel Dashboard 租户 QPS 可见

---

## Task 3.6: 审计扩展

**Files:**
- Create: RocketMQ topic 配置 `sunshine-tool-audit`
- Modify: `orchestrator/.../audit/` — tool 调用细项
- Create: ES 索引 mapping `tool-audit-*`
- Modify: `orchestrator/.../audit/` — 与 3.10.6、3.9.4 联动

- [ ] **3.6.1** `sunshine-tool-audit`：toolId、params（脱敏）、output 摘要、traceId
- [ ] **3.6.2** ES 按 `conversationId` 查询 API
- [ ] **3.6.3** `sub_agent_run` + `plan.*` 四类事件（见 multi-agent plan 3.10.6、3.9.4）

---

## Task 3.7: Grounding 校验

**Files:**
- Create: `orchestrator/.../grounding/AnswerGroundingChecker.java`
- Test: `orchestrator/src/test/java/.../AnswerGroundingCheckerTest.java`

- [ ] **Step 1:** 单测：无 tool/rag 步骤时含金额/制度名 → 拦截
- [ ] **Step 2:** 子 Agent output 交下游 llm 前校验钩子
- [ ] **Step 3:** 集成测试挂接 `ReactExecutor` / workflow answer 节点

---

## Task 3.8: 提示词改写

**Files:**
- Create: `orchestrator/.../rewrite/QueryRewriteService.java`
- Create: `orchestrator/.../prompt/PromptComposer.java`
- Modify: `orchestrator/.../SunshineAgent.java` 或 `LlmGatewayClient.java`
- Modify: `docs/nacos/sunshine-orchestrator.yaml` — `agent.rewrite.*`

- [ ] **3.8.1** `QueryRewriteService`：`rag` / `intent` / `empty-recall`（与 3.4.7 联动）
- [ ] **3.8.2** `PromptComposer`：base → mode → skill → memory
- [ ] **3.8.3** workflow `llm` 节点 prompt 走 Composer

---

## Task 3.9–3.12: 多 Agent / Skills / 前端

→ 完整 Task 见 [2026-06-19-multi-agent-architecture.md](./2026-06-19-multi-agent-architecture.md)

---

## Task 3.13: 并行（不进检查门）

- [ ] `desensitize-service` AhoCorasick + 可配置规则库
- [ ] Milvus `source_type` metadata 可空字段（3.4.2 一并做更佳）

---

## Task 3.14: Redis GenerationJob 分布式锁

**Files:**
- Modify: `orchestrator/.../generation/GenerationRegistry.java`
- Create: `orchestrator/.../generation/DistributedGenerationLock.java`（Redis）
- Test: 多实例抢锁单测

- [ ] **3.14.1** 仅一个实例持有 Job flush 权
- [ ] **3.14.2** 多实例集成测试（可选，生产必做）

---

## 阶段三总验收

```bash
python scripts/rag_eval.py --suite all --strategy hybrid+rerank --gate
python scripts/phase2_agent_demo.py --suite all
mvn test -pl orchestrator,rag-service
```

对照 [phase3 SSOT §6](../specs/phase3-production-hardening-design.md) 17 条检查门逐项勾选。

---

## Spec Coverage Self-Review

- [x] 3.2–3.8 均有 Files + 验收（本次补全）
- [x] 3.4.1 含完整命令示例
- [x] 3.9–3.12 指向 multi-agent plan
- [x] 17 条检查门在 coverage audit 中可追溯
- [ ] 3.4.2+ 逐步 TDD 代码块 — 实施周按 Task 3.4.1 模板展开
