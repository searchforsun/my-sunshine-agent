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

## 实施进度（2026-06-27）

| 区域 | 代码 | Live/检查门 | 说明 |
|------|:----:|:-----------:|------|
| **3.4** RAG 全链路 | ✅ | v5 ✅；v6 提升轨 WARN | `docs/rag/regression-2026-06-21.md` |
| **3.8** 提示词 3.8.1–3.8.7 | ✅ | — | QueryRewrite / PromptComposer / HyDE / Planner 改写 |
| **3.9–3.12** 多 Agent / Skills / 前端 | ✅ | 部分 live ⬜ | → [multi-agent-architecture.md](./2026-06-19-multi-agent-architecture.md) |
| **3.6** 审计三链路 API | ✅ | 可查 live ⬜ | tool.call / sub_agent_run / plan.* |
| **3.2** 多租户 | **部分** | ⬜ | 传播链 + 过滤已实现；缺跨租户集成测试 |
| **3.3** HITL | ✅ | ⬜ | 全栈代码；`verify_hitl_live.py` |
| **3.7** Grounding | ✅ | 集成测试 ⬜ | `AnswerGroundingChecker` 已接入 |
| **3.5** 可观测 | **部分** | ⬜ | 指标+JSON ✅；Docker/远程部署未闭环 |
| **3.9.5** 暂停/续跑 | **部分** | ⬜ | 基础续跑 ✅；pausePhase 等未做 |
| **3.13** AhoCorasick | ⬜ | — | `source_type` ✅ |
| **3.14** 多实例锁 | ⬜ | — | 进程内锁，非 Redis |

**下一迭代 P0**：**3.9.5** → 3.2 集成测试 → 3.3/3.5/3.11 live → 3.7 集成测试 → 3.14。

---

## 排期总览

**原 8 周表（设计基准）** — 主线开发已完成，剩余 live / 3.9.5 / 3.13 / 3.14 见上方进度表。

```
周 1–5   3.4 RAG + 3.8.1 + 指标                    ← ✅
周 6–7   3.9 / 3.10 / 3.11 / 3.12 / 3.6 API        ← ✅
周 7     3.3 HITL + 3.2 多租户（代码）              ← 代码 ✅；live ⬜
周 8     3.7 Grounding + 3.5 部署 + 总检查门       ← 部分
```

**剩余主线**：3.9.5 → 3.2 集成测试 → live 验收（3.3/3.5/3.11/审计）→ 3.7 集成测试 → 3.14 → 3.13

---

## Task 3.4.1: golden-set v6 + rag_eval 双轨

**Files:**
- Modify: `scripts/rag_eval.py`
- Modify: `docs/rag/golden-set.yaml`
- Modify: `docs/rag/baseline-report.md`

- [x] **Step 1:** 在 `golden-set.yaml` 增加 `adversarial` 分类 ≥40 条（口语变体、专有名词、跨制度 multihop）
- [x] **Step 2:** `rag_eval.py` 增加 `--suite v5|v6|all` 与 `--strategy vector|hybrid|hybrid+rerank`
- [x] **Step 3:** 跑 vector 基线并写入 `baseline-report.md`

```bash
set RAG_URL=http://localhost:8400
python scripts/rag_reset.py
python scripts/rag_ingest_bulk.py
python scripts/rag_eval.py --suite v5 --strategy vector --report-md
```

Expected: 退出码 0；报告含 Recall@5/MRR/P95

- [x] **Step 4:** `mvn test -pl rag-service` 绿

---

## Task 3.4.2: 入库 metadata + ES 双写

**Files:**
- Modify: `rag-service/src/main/java/com/sunshine/rag/service/MilvusService.java`
- Modify: `rag-service/src/main/java/com/sunshine/rag/controller/IngestionController.java`
- Create: `rag-service/src/main/java/com/sunshine/rag/service/ElasticsearchIndexService.java`
- Modify: `docs/nacos/sunshine-rag.yaml`
- Test: `rag-service/src/test/java/com/sunshine/rag/...`（新建 ES 双写单测）

- [x] **Step 1:** Milvus schema 增加 `tenant_id`, `section_path`, `chunk_index`, 可空 `source_type`
- [x] **Step 2:** 入库路径双写 ES（content + metadata）
- [x] **Step 3:** 单测：mock ES 断言 index 调用
- [ ] **Step 4:** 验收：`POST` 上传后 ES `_count` > 0（可选联机）

---

## Task 3.4.3–3.4.5: BM25 + Hybrid + Rerank

**Files:**
- Create: `rag-service/.../Bm25SearchService.java`
- Create: `rag-service/.../HybridRetrievalService.java`
- Create: `rag-service/.../RerankService.java`
- Modify: `rag-service/.../RetrievalService.java`
- Modify: `docs/nacos/sunshine-rag.yaml`

- [x] **3.4.3** ES match 查询 + `RetrievalCandidate` 统一模型 + 单测
- [x] **3.4.4** RRF 融合 + `rag.search.strategy` 开关 + `rag_eval --strategy hybrid`
- [x] **3.4.5** Rerank top-20→5 + v5/v6 对比报告（closure 见 `baseline-report.md`）

**验收:** v5 hybrid+rerank 不退化；v6 较 vector Recall@5 +15%（**closure：生产门禁 PASS；相对提升轨 WARN**）

---

## Task 3.4 closure 备注

- v5 / v6 **生产门禁**：PASS（`baseline-20260621-142111-closure-v6.json`）
- v6 **vs vector 提升轨**：WARN（vector Recall@5 已 97.6%，`--compare-vector-json` 未达 +5pp）
- 报告：`docs/rag/baseline-report.md` §Closure、`docs/rag/regression-2026-06-21.md`

---

## Task 3.4.6–3.4.8: Metrics + CI

**Files:**
- Modify: `rag-service` — Micrometer `rag_search_*` 指标
- Create: `docs/grafana/rag-dashboard.json`（或 prometheus 规则片段）
- Modify: `scripts/rag_eval.py` — `--fail-if-recall5-below`
- Create: `.github/workflows/rag-eval.yml`（或文档说明本地门禁）

- [x] **3.4.6** Micrometer `rag_search_*` + `docs/grafana/` 面板/告警（本地方案 B：`curl :8400/actuator/prometheus`）
- [x] **3.4.8** CI 门禁 + `docs/rag/regression-2026-06-21.md` + `.github/workflows/rag-eval.yml`

---

## Task 3.2: 多租户隔离

**Files:**
- Modify: `rag-service/.../RetrievalService.java`, `IngestionController.java`, `MilvusService.java`
- Modify: `orchestrator/.../memory/MemoryMessageBuilder.java` 或 `MtmService` — tenant 过滤
- Modify: `gateway/...` Sentinel 租户 QPS 规则
- Modify: `docs/nacos/sunshine-gateway.yaml`, `sunshine-rag.yaml`
- Test: `rag-service` 跨租户集成测试

- [x] **3.2.1** 检索/入库带 `x-tenant-id`（Gateway JWT → BFF → orchestrator → rag-service；未登录 `anonymous`）
- [x] **3.2.2** MTM 向量召回同 tenant 过滤（`MtmService` + memory Milvus `tenant_id`）
- [x] **3.2.3** 集成测试：租户 A 语料，租户 B 检索 0 命中（`TenantSearchQueryTest` + `TenantIsolationIntegrationTest`）

**实现备注**：Milvus 为 `tenant_id` 字段 + expr 过滤，非 Partition API。Live：`scripts/verify_tenant_live.py`。

```bash
mvn test -pl rag-service -Dtest=*Tenant*
```

---

## Task 3.3: HITL 写操作确认

**Files:**
- Modify: `tool-manager/...` Catalog DTO — `sideEffect: read|write` ✅
- Modify: `docs/nacos/sunshine-tools.yaml` — 写工具 `approve_oa_task` ✅
- Create: `orchestrator/.../hitl/HitlConfirmationService.java` ✅（非独立 PreToolCallHook）
- Modify: `orchestrator/.../controller/ChatController.java` — `POST /api/chat/confirm-tool` ✅
- Modify: `sunshine-ui` — `HitlStepActions.vue` + SSE `type:confirmation` ✅
- Modify: `bff/...` — 透传 confirm-tool ✅

- [x] **3.3.1** Catalog `sideEffect` + 种子写工具 `approve_oa_task`
- [x] **3.3.2** `HitlConfirmationService` + Redis 令牌；子 Agent / Workflow tool 节点拦截
- [x] **3.3.3** 前端确认 → 恢复；拒绝/超时 → SKIP

**验收:** `scripts/verify_hitl_live.py`；检查门 live ⬜。3.9.5 `pendingInteraction` 续跑 ⬜。

---

## Task 3.5: Grafana + Sentinel

**Files:**
- Modify: `rag-service` metrics（见 3.4.6）
- Create/Modify: Prometheus 规则 + Grafana dashboard JSON
- Modify: `docs/nacos/sunshine-gateway.yaml` — Sentinel 租户 QPS

- [ ] **3.5.1** Grafana RAG 专区 6 面板有数据（**指标+JSON ✅**；ecs4c16g 部署 ⬜，见 `docs/grafana/README.md` 方案 B）
- [ ] **3.5.2** 4 条告警可触发（`rag-alerts.yml` 已编写 ⬜ 联调触发）
- [ ] **3.5.3** Sentinel Dashboard 租户 QPS 可见

---

## Task 3.6: 审计扩展

**Files:**
- Create: RocketMQ topic 配置 `sunshine-tool-audit`
- Modify: `orchestrator/.../audit/` — tool 调用细项
- Create: ES 索引 mapping `tool-audit-*`
- Modify: `orchestrator/.../audit/` — 与 3.10.6、3.9.4 联动

- [x] **3.6.1** `tool.call`：toolId、params（脱敏）、output 摘要（复用 sunshine-audit topic）
- [x] **3.6.2** `GET /api/audit/tool-calls?messageId=` / `?conversationId=` 查询
- [x] **3.6.3** `sub_agent_run` + `plan.*` 事件（见 multi-agent plan 3.10.6、3.9.4）

---

## Task 3.7: Grounding 校验

**Files:**
- Create: `orchestrator/.../grounding/AnswerGroundingChecker.java` ✅
- Test: `AnswerGroundingCheckerTest.java` ✅；`AgentNodeHandlerTest`（子 Agent）✅

- [x] **Step 1:** 单测：无 tool/rag 步骤时含金额/制度名 → 拦截
- [x] **Step 2:** 子 Agent output 校验（`AgentNodeHandler`）
- [ ] **Step 3:** 集成测试挂接 `ReactExecutor` / workflow answer 节点（检查门 ⬜）

---

## Task 3.8: 提示词改写

### 3.8.1 / 3.4.7 — QueryRewrite（✅ 已完成）

**Files:**
- `orchestrator/.../rewrite/QueryRewriteService.java` ✅
- `orchestrator/.../rag/KnowledgeRetrievalService.java` ✅
- `orchestrator/.../routing/ExecutionPlanRouter.java` ✅
- `orchestrator/.../config/AgentRewriteProperties.java` ✅
- `docs/nacos/sunshine-orchestrator.yaml` — `agent.rewrite.*` ✅

- [x] **3.4.7 / 3.8.1** `rag` / `intent` / `empty-recall`（**默认开启**）
- [x] 单测：`QueryRewriteServiceTest`、`KnowledgeRetrievalServiceTest`、`ExecutionPlanRouterTest`

```bash
mvn test -pl orchestrator -Dtest=QueryRewriteServiceTest,KnowledgeRetrievalServiceTest,ExecutionPlanRouterTest
```

---

### 3.8.2 — PromptComposer（✅ 已完成）

**Goal:** 统一 system / memory 消息拼装，替代 `SunshineAgent.buildInputs` 与 `LlmGatewayClient` 内散落逻辑。

**Files:**
- Create: `orchestrator/.../prompt/PromptComposer.java` ✅
- Create: `orchestrator/.../prompt/PromptComposeRequest.java` ✅
- Create: `orchestrator/.../config/PromptOverlayProperties.java` ✅
- Modify: `orchestrator/.../agent/SunshineAgent.java` ✅
- Modify: `orchestrator/.../client/LlmGatewayClient.java` ✅
- Modify: `docs/nacos/sunshine-orchestrator.yaml` — `agent.prompt.*` ✅
- Test: `orchestrator/.../prompt/PromptComposerTest.java` ✅

- [x] **Step 1:** 定义 6 层叠加顺序与优先级（见 spec §3.8 PromptComposer 表）；**3.11 前 skill-overlay 可 no-op**
- [x] **Step 2:** ReAct / simple-llm 路径接入；`mvn test -pl orchestrator -Dtest=PromptComposerTest`
- [x] **Step 3:** 与现有 `agent.system-prompt`、`MemoryComposer` 行为回归一致（`PromptComposerTest` + `ReActAgentRuntimeTest`）

**验收:** ReAct 对话 system 块结构与改前一致；无重复注入 STM/LTM。

---

### 3.8.3 — workflow llm 走 Composer（✅ 已完成）

**Files:**
- Modify: `orchestrator/.../execution/handler/LlmNodeHandler.java` ✅
- Modify: `orchestrator/.../client/LlmGatewayClient.java` — `completeComposed()` ✅
- Modify: `orchestrator/.../prompt/PromptComposeRequest.java` — `forWorkflowLlm()` ✅
- Modify: `docs/nacos/sunshine-workflows.yaml` — llm 节点去掉重复 `用户问题` 行 ✅
- Test: `LlmNodeHandlerTest` ✅

- [x] **Step 1:** `LlmNodeHandler` 用 Composer 第 6 层渲染 `{{rag.output}}` 等占位后调 LLM
- [x] **Step 2:** `knowledge-qa` workflow 用户提问改由 Composer user 层注入（yaml 去重）
- [x] **Step 3:** `mvn test -pl orchestrator -Dtest=LlmNodeHandlerTest,WorkflowExecutorTest,PromptComposerTest`

**验收:** workflow 回答仍仅依据检索/工具结果；禁止 Controller 硬编码模板。

---

### 3.8.4–3.8.7 — 增强（**✅** 非检查门）

| 子任务 | 内容 | 依赖 | Files（规划） |
|--------|------|------|----------------|
| **3.8.4** | Timeline `detail` 展示改写前后 query；审计 `rewriteApplied` / `rewriteLatencyMs` | 3.8.1 | **✅** |
| **3.8.5** | `rag_eval` 或脚本输出 `raw_query` vs `rewritten_query` 报告 | 3.8.1 | **✅** |
| **3.8.6** | `rag` HyDE：**首次 0 命中 fallback**（`KnowledgeRetrievalService` 链：rag 改写 → 首检 → HyDE → empty-recall） | 3.8.1 | **✅** |
| **3.8.7** | Planner 前 QueryRewrite | 3.9.1, 3.10.4 | `PlannerAgentRuntime` |

- [x] **3.8.4** Timeline + 审计字段
- [x] **3.8.5** 改写评测报告
- [x] **3.8.6** HyDE fallback（`agent.rewrite.rag.hyde.enabled`；首检 0 命中再 HyDE，默认 false）
- [x] **3.8.7** Planner 前改写

---

## Task 3.9–3.12: 多 Agent / Skills / 前端

→ 完整 Task 见 [2026-06-19-multi-agent-architecture.md](./2026-06-19-multi-agent-architecture.md)

---

## Task 3.13: 并行（不进检查门）

- [x] Milvus `source_type` metadata 可空字段（已在 Task 3.4.2 schema 落地）
- [x] `desensitize-service` AhoCorasick + 可配置规则库（Nacos `desensitize.rules`）

---

## Task 3.14: Redis GenerationJob 分布式锁

**Files:**
- Modify: `orchestrator/.../generation/GenerationRegistry.java`
- Create: `orchestrator/.../generation/DistributedGenerationLock.java`（Redis）
- Test: 多实例抢锁单测

- [x] **3.14.1** 仅一个实例持有 Job flush 权（Redis key `sunshine:generation:lock:{generationId}`，TTL 30s）
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
- [x] 3.4.2+ TDD 代码块 — Task 3.4 closure ✅
