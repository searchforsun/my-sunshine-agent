# 阶段三：生产加固 — 技术设计（SSOT）

> **周期**：8 周（兼职 1–2 天/周；检查门严格全过）  
> **状态**：🟡 **进行中**（2026-06-27 代码审计：主线 3.4/3.8/3.9/3.10/3.11/3.12/3.6 API ✅；3.2/3.3/3.7 代码 ✅ live ⬜；3.9.5/3.13/3.14 未做或部分）  
> **前置**：[阶段二](./phase2-benchmark-design.md) 收尾完成；golden-set v5 基线全 PASS  
> **主轴**：RAG 双轨评测 + **PLAN_WORKFLOW** + 多租户 / HITL / 全链路可观测

---

## 0. 实施进度总览（2026-06-27）

| 任务卡 | 摘要 | 代码 | Live/检查门 | 计划文档 |
|--------|------|:----:|:-----------:|----------|
| **3.4** RAG 3.4.1–3.4.8 | 评测 / ES / Hybrid / Rerank / Metrics / CI | **✅** | v5 ✅；v6 提升轨 WARN | [phase3-production-hardening.md](../plans/2026-06-19-phase3-production-hardening.md) Task 3.4 |
| **3.8** 提示词 3.8.1–3.8.7 | QueryRewrite / PromptComposer / HyDE / Planner 改写 | **✅** | — | 同上 Task 3.8 |
| **3.9** PLAN_WORKFLOW | Planner + 持久化 + 三 API + 重试/降级/Recovery | **✅** 3.9.1–3.9.4 | 2+ agent live ⬜ | [multi-agent-architecture.md](../plans/2026-06-19-multi-agent-architecture.md) §3.9 |
| **3.9.5** 暂停/续跑一致性 | pausePhase / pendingInteraction / 按钮分态 | **部分** | ⬜ | [pause-resume-consistency.md](../plans/2026-06-26-pause-resume-consistency.md) |
| **3.10** AgentRuntime | MAIN/SUB/PLANNER + 工具白名单 | **✅** | — | multi-agent §3.10 |
| **3.11** skill-manager | :8225 + Catalog + 六种触发 | **✅** | Live ⬜ | multi-agent §3.11 |
| **3.12** 前端 | `/skills` + Plan DAG/抽屉 + 执行模式 | **✅** | `/skills` live ⬜ | multi-agent §3.12 |
| **3.6** 审计扩展 | tool.call / sub_agent_run / plan.* + 查询 API | **✅** | 可查 live ⬜ | Task 3.6 |
| **3.2** 多租户 | `tenant_id` 字段隔离 + MTM + Sentinel QPS | **部分** | 跨租户集成测 + live ⬜ | Task 3.2 |
| **3.3** HITL | sideEffect + 确认 UI（主/子 Agent） | **✅** | live ⬜ | Task 3.3 |
| **3.7** Grounding | AnswerGroundingChecker | **✅** | 集成测试 ⬜ | Task 3.7 |
| **3.5** 可观测 | Micrometer + Grafana/告警 JSON | **部分** | 远程部署/触发 ⬜ | Task 3.5；[grafana/README.md](../../grafana/README.md) |
| **3.13** 并行 | AhoCorasick；`source_type` 可空 | **部分** | `source_type` ✅；AhoCorasick ⬜ | Task 3.13 |
| **3.14** 多实例锁 | Redis GenerationJob 分布式锁 | **⬜** | — | Task 3.14 |

**实现备注（3.2）**：Milvus 采用 `tenant_id` 字段 + expr 过滤，**非** Milvus Partition API；Gateway 未登录注入 `anonymous`（非 `default`）。

**closure 备注**：v5/v6 **生产门禁** PASS（`docs/rag/regression-2026-06-21.md`）；v6 **相对 vector 提升轨** WARN（vector 基线已 97.6%）。

---

## 1. 目标与非目标

| # | 目标 | 验收 |
|---|------|------|
| G1 | RAG 可量化、可回归 | v5 不退化 + v6 难例 hybrid+rerank 较 vector +15% |
| G2 | 检索可观测 | Grafana RAG + Sentinel Dashboard + 4 告警 |
| G3 | 隔离与管控 | 租户 `tenant_id` 逻辑隔离；写工具 HITL（含子 Agent） |
| G4 | 复杂任务可控 | PLAN_WORKFLOW + skill-manager + 2+ agent 节点 |
| G5 | 可审计可回放 | tool / sub_agent / plan.* + Plan 详情页 |

**非目标**：Docker 沙箱、MsgHub、K8s/MCP/Seata、Prompt 运营后台、IfElse 分支（→ 阶段四；**MCP 动态引入与 `/mcp` 前端管理** → 阶段四 **4.8**）

---

## 2. 任务总览

| 任务卡 | 摘要 | 优先级 |
|--------|------|:------:|
| **3.1** | （保留编号）已合并入 **3.9** PLAN_WORKFLOW | — |
| **3.2** | 多租户：`tenant_id` 字段隔离 + MTM tenant + Sentinel QPS | P0 |
| **3.3** | HITL：Catalog `sideEffect` + 确认 UI（主/子 Agent） | P0 |
| **3.4** | **RAG 检索增强**（子任务 3.4.1–3.4.8） | **P0 优先** |
| **3.5** | Grafana RAG 专区 + Sentinel Dashboard + 4 告警 | P0 |
| **3.6** | 审计：tool-audit + sub_agent_run + plan.* | P0 |
| **3.7** | Grounding：主答复 + 子 Agent output | P0 |
| **3.8** | 提示词：`QueryRewriteService` + `PromptComposer` | P1 |
| **3.9** | PLAN_WORKFLOW：Planner + 动态 DAG + Plan 持久化 | P0 |
| **3.10** | AgentRuntime：MAIN/SUB/PLANNER + 工具白名单 | P0 |
| **3.11** | skill-manager :8225 + SkillCatalogService | P0 |
| **3.12** | 前端 `/skills` + Plan 详情页 | P1 |
| **3.13** | 并行：AhoCorasick、`source_type` 预留 | P2 |
| **3.14** | 多实例：Redis GenerationJob 分布式锁 | P2* |

\*多实例生产必做

**依赖**：3.4.2 → 3.2；3.10 + 3.11 → 3.9；3.4 优先于 3.9。

---

## 3. 排期（8 周）

> **实际（2026-06-27）：** 3.4 / 3.8 / 3.9 / 3.10 / 3.11 / 3.12 / 3.6 API 已落地；剩余见 §0：**3.9.5**、**3.2 live**、**3.3/3.5 live**、**3.7 集成测试**、**3.13/3.14**。下表为设计基准排期。

| 周 | 主线 |
|:--:|------|
| 1 | 3.4.1 评测 + 3.10.1 AgentRuntime |
| 2 | 3.4.2 ES 双写 + 3.10.2–3.10.3 子 Agent 子集 |
| 3 | 3.4.3–3.4.4 Hybrid + **3.11** skill-manager |
| 4 | 3.4.5 Rerank + 3.8.1 QueryRewrite + 3.5.1 Grafana |
| 5 | 3.4.8 CI + **3.2** 多租户 + 3.5.2 Sentinel/告警 |
| 6 | 3.4 验收 + **3.9** PLAN_WORKFLOW + 3.8.2 PromptComposer |
| 7 | **3.3** HITL + **3.6** 审计 |
| 8 | **3.7** Grounding + **3.12** 前端 + 总检查门 |

---

## 4. 任务详设

### 3.2 多租户隔离（代码 **部分 ✅**，检查门 ⬜）

- Milvus / ES：`tenant_id` 字段 + 检索 expr / term 过滤（**非** Milvus Partition API）
- 入库/检索强制 `x-tenant-id`（Gateway JWT 注入 → BFF → orchestrator → rag-service）
- MTM 向量召回同 tenant 过滤（`MtmService` + Milvus memory collection）
- Gateway Sentinel 租户 QPS（`sunshine-gateway-gw-flow-rules.json` + Nacos）

**`x-tenant-id` 传播链（已实现）：** Gateway `TenantIdResolver`（登录用户 JWT `tenantId`；未登录 `anonymous`）→ BFF `OrchestratorClient` 透传 → orchestrator 各 Controller → `RagClient` header + body。

**缺口**：跨租户 `@SpringBootTest`（A 入库 B 检索 0 命中）；`scripts/verify_tenant_live.py` live 未关检查门。

### 3.3 Human-in-the-Loop（代码 **✅**，live ⬜）

- Catalog `sideEffect: read | write`（`ApproveOaTaskToolHandler` 写工具种子 `approve_oa_task`）
- `HitlConfirmationService`（Redis token + 阻塞等待）→ SSE `type:confirmation`（非独立 `PreToolCallHook` 类）
- `POST /api/chat/confirm-tool`；ReAct / Workflow tool 节点 / **子 Agent** 写工具均拦截
- 前端：`HitlStepActions.vue`、`PlanNodeDrawer.vue`；`scripts/verify_hitl_live.py`

**SSE 确认事件：** `{ "type":"confirmation", "toolId", "paramsSummary", "confirmationToken", "expiresAt" }`；`confirm-tool` body：`{ "token", "approved": true|false }`；超时/拒绝 → 步骤 `SKIP`。

**关联 3.9.5**：HITL 停止后续跑恢复同一交互（`pendingInteraction`）⬜，见 §3.9.5。

### 3.4 RAG 检索增强

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **3.4.1** | 增强 `rag_eval.py`：`--suite v5\|v6\|all`；Recall/MRR/EmptyRate/latency | ✅ |
| **3.4.2** | 入库 metadata + ES 双写；可空 `source_type`（阶段四 OCR 预留） | ✅ |
| **3.4.3** | `Bm25SearchService` | ✅ |
| **3.4.4** | `HybridRetrievalService` + RRF；Nacos `rag.search.strategy` | ✅ |
| **3.4.5** | `RerankService` + 向量锚点门禁 | ✅ |
| **3.4.6** | Micrometer + `docs/grafana/` 面板/告警 JSON（远程部署方案 B 可选） | ✅ |
| **3.4.7** | Query Rewrite `empty-recall`（与 **3.8** 联动） | ✅ |
| **3.4.8** | CI `--fail-if-recall5-below`；`regression-YYYY-MM-DD.md`；`rag-eval.yml` | ✅ |

**双轨门禁**：

| 轨道 | 数据集 | 门禁 |
|------|--------|------|
| v5 回归 | 123 条 | hybrid+rerank：Recall@5≥0.98，MRR≥0.92，正例 Empty=0 |
| v6 提升 | ≥40 条 adversarial | hybrid+rerank 较 vector Recall@5 +15%，MRR +10% |
| 性能 | 合并 | P95 ≤ 800ms |

**3.4.1 含**：扩展 golden-set → v6。

### 3.5 可观测（部分 ✅）

| 子项 | 状态 |
|------|:----:|
| Micrometer `rag_search_*` / `rag_empty_total` / `rag_rerank_duration_seconds` | ✅ |
| `docs/grafana/rag-dashboard.json` + `rag-alerts.yml` | ✅ |
| 远程 Grafana 6 面板有数据 | ⬜ 可选；本地 [grafana/README.md](../../grafana/README.md) 方案 B |
| 4 条 Prometheus 告警可触发 | ⬜ |
| Sentinel Dashboard 租户 QPS | ⬜ |

### 3.6 审计扩展（API **✅**，live ⬜）

- `tool.call`：`ToolAuditService` → RocketMQ / MySQL / ES；`GET /api/audit/tool-calls`
- `sub_agent_run`：`SubAgentAuditService`；`GET /api/audit/sub-runs`
- `plan.*`：`PlanExecutionAuditService`（created/validated/completed/failed/node.attempt 等）
- 汇总：`AuditController` + `GET /api/audit/recent`

### 3.7 Grounding（代码 **✅**，集成测试 ⬜）

- `AnswerGroundingChecker`：企业数据表述须有 tool/rag 支撑
- 已接入：`ReActAgentRuntime`、`WorkflowExecutor` answer 节点、`AgentNodeHandler`（子 Agent output）
- Nacos：`agent.grounding.*`；单测：`AnswerGroundingCheckerTest`、`AgentNodeHandlerTest`
- **缺口**：`ReactExecutor` / workflow answer 路径集成测试；检查门「子 Agent 不污染主 reasoning」

### 3.8 提示词改写

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **3.4.7** | empty-recall 二次检索（与 3.8.1 联动，`KnowledgeRetrievalService`） | ✅ |
| **3.8.1** | `QueryRewriteService`：`rag` / `intent` / `empty-recall`；`agent.rewrite.*` **默认开启** | ✅ |
| **3.8.2** | `PromptComposer`：base → mode → skill → memory → scope（抽离 `SunshineAgent.buildInputs` / `MemoryMessageBuilder`） | **✅** |
| **3.8.2b** | `skill-overlay` 层（3.11 后走 SkillCatalog） | **✅** |
| **3.8.3** | workflow `llm` 节点 prompt 走 Composer 第 6 层 `node-prompt`（`LlmNodeHandler`） | **✅** |
| **3.8.4** | QueryRewrite 可观测（**非检查门**）：Timeline `detail` 改写前后 query；审计 `rewriteApplied` / `rewriteLatencyMs` | **✅** |
| **3.8.5** | QueryRewrite 评测（**非检查门**）：golden-set `raw_query` vs `rewritten_query` 对比报告 | **✅** |
| **3.8.6** | `rag` 可选 HyDE：**首次 0 命中 fallback**（非首检前覆盖 rag 改写 query；**非检查门**） | **✅** |
| **3.8.7** | Planner 前 QueryRewrite（`WorkflowPlanner` + `agent.rewrite.planner`） | **✅** |

**PromptComposer 叠加顺序**（3.8.2 锁定）：

```
1. base-system   ← agent.system-prompt
2. mode-overlay  ← simple | workflow:{id} | react
3. skill-overlay ← skill-manager 启用时（3.11）
4. memory-layers ← LTM / MTM / STM（已有 MemoryMessageBuilder）
5. scope-prompt  ← 作答边界
6. node-prompt   ← workflow llm 节点模板（3.8.3）
```

实施计划见 [2026-06-19-phase3-production-hardening.md](../plans/2026-06-19-phase3-production-hardening.md) Task 3.8。

### 3.9 PLAN_WORKFLOW（含原 3.1）（**✅** 3.9.1–3.9.4）

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **3.9.1** | `PLAN_WORKFLOW` 执行模式 + IntentRouter 路由 | **✅** |
| **3.9.2** | `execution_plan` 表持久化 + `chat_message.execution_plan_id` | **✅** |
| **3.9.3** | 三 API 回放 + Timeline `planId` / `planNodeId` + Trace | **✅** |
| **3.9.4** | `plan.*` 审计事件（RocketMQ） | **✅** |
| **静态 workflow** | `StaticPlanAdapter` 物化 Plan → 同 `PlanWorkflowPanel` / `PlanDagGraph` | **✅** |
| **重试/降级** | `NodeRetryExecutor` + Replan + `degraded_react` / `completed_with_errors` | **✅** |
| **用户确认** | `PlanApprovalService` + `confirm-plan` + 确认框 / 重新生成 UX · [design](./2026-06-27-plan-user-approval-design.md) | **✅** |

**锁定决策**（原 `locked-architecture-decisions` D1–D2）：

- `ExecutionMode.PLAN_WORKFLOW` 第四分支；IntentRouter 可输出 `plan-workflow`
- Planner：`agent.planner.model: deepseek-v4-flash`；失败 **fallback react**
- `DAGValidator` → `DynamicWorkflowExecutor`（线性 MVP）
- `execution_plan` 表 + `chat_message.execution_plan_id`
- API：`GET /api/execution-plans/{id}`、`?conversationId=`、`/{id}/nodes`
- Timeline：`planId`、`planNodeId`；**可点击** Plan 详情
- Trace：`plan.planner` / `plan.validate` / `plan.node.*`
- 演示：制度 + 财务 + 合规，**2+ agent 节点**

### 3.9.5 阶段三收尾：暂停/续跑一致性（**部分**）

> SSOT：[2026-06-26-pause-resume-consistency-design.md](./2026-06-26-pause-resume-consistency-design.md) · 实施：[2026-06-26-pause-resume-consistency.md](../plans/2026-06-26-pause-resume-consistency.md)

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **基础续跑** | `WorkflowCheckpoint(resumeNodeId, wfCtxJson)` + `resumePaused`（EXECUTING DAG） | **✅** |
| **3.9.5.1** | `WorkflowCheckpoint.pausePhase`（PLANNING / EXECUTING）；Planner 阶段 markPaused | ⬜ |
| **3.9.5.2** | `pendingInteraction`（hitl / recovery）；停止保留 awaiting；续跑 re-await | ⬜ |
| **3.9.5.3** | 续跑按钮分态（有 checkpoint →「继续执行」；无 →「重新生成」）；awaiting 优先于 paused | ⬜ |
| **3.9.5.4** | ReAct stop 后 think/tool 步骤终态；wfCtx 空拒绝 Plan 检查点续跑 | ⬜ |

**前置（✅）**：节点 Recovery 重试/跳过、基础 pause/resume（仅 running 时落 checkpoint）、HITL/Recovery UI、`nodeAttempts` SSE。

**缺口**：`pausePhase` / `PendingInteraction` / `resolveResumeMode` / `verify_pause_resume_consistency.py` 均未实现；Planner 阶段 stop 不可续跑。

**非本任务**：ReAct 逐步 checkpoint → 阶段四 **4.7.5 TaskBoard**。

### 3.10 多 Agent 运行时（**✅**）

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **3.10.1** | `AgentRuntime` + `AgentRole` MAIN\|SUB\|PLANNER | **✅** |
| **3.10.2** | `ReActAgentFactory` overlay + 工具白名单 | **✅** |
| **3.10.3** | `AgentNodeHandler`：`skillId` / `tools` / `maxIters` / `systemOverlay` | **✅** |
| **3.10.4** | `PlannerAgentRuntime` + `WorkflowPlanner` | **✅** |
| **3.10.5** | 多 agent 节点（`WorkflowExecutor` / Nacos 双 agent 示例） | **✅**（live ⬜） |
| **3.10.6** | `sub_agent_run` 审计事件 | **✅** |
| **3.10.7** | 上下文隔离：无 STM/LTM、skill→Composer、不写主 reasoning | **✅** |

子 Agent 默认 maxIters=**4**。实现目标 SSOT 见 [multi-agent plan §子 Agent 实现目标](../plans/2026-06-19-multi-agent-architecture.md#子-agent-实现目标ssot)。

详设历史稿：`2026-06-19-multi-agent-architecture-design.md`  
实施计划：`../plans/2026-06-19-multi-agent-architecture.md`（3.9.x / 3.10.x / 3.11.x / 3.12.x）

### 3.11 skill-manager ✅

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **3.11.1** | CRUD + 上传 + 版本/delete/download API | ✅ |
| **3.11.2** | `GET /api/skills/catalog` | ✅ |
| **3.11.3** | 种子 skill | ✅ |
| **3.11.4** | orchestrator `SkillCatalogService` 缓存 | ✅ |
| **3.11.6** | Catalog index / detail 拆分 | ✅ |
| **3.11.7** | `@` + 强提示绑定 + 六种触发 | **✅**（Live ⬜） |

- 模块 **:8225**；MySQL + MinIO/本地存储
- 详设 API 表见 [locked D3](./2026-06-19-locked-architecture-decisions.md#d3-skills-服务端管理--前端运营页)

### 3.12 前端（**✅**，live 部分 ⬜）

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **3.12.1** | 列表 + 上传 + 预览 + 版本运营 | **✅** |
| **3.12.1a** | 元数据修改、下载/删除版本、卡片删除 | **✅** |
| **3.12.2** | 版本 diff 独立页 `/skills/:skillId/diff` | **✅** |
| **3.12.3** | ~~工具绑定~~（已取消） | — |
| **3.12.4** | Plan DAG + `PlanNodeDrawer`（Chat 内嵌 + `/plans/:planId`） | **✅** |
| **Chat** | `@` skill、`ExecutionModeSelector` 五模式底栏 | **✅** |

**UI SSOT**：[skills-management-ui-design.md](./skills-management-ui-design.md)

### 3.13 并行（不进检查门）

| 子项 | 状态 |
|------|:----:|
| Milvus `source_type` metadata 可空字段 | ✅（3.4.2 schema） |
| desensitize AhoCorasick + 可配置规则库 | ⬜ |

### 3.14 多实例 Job 锁 ⬜

- Redis 分布式 GenerationJob 锁（生产多实例必做）
- **锁定：** key `sunshine:generation:lock:{jobId}`；TTL 30s + 续期；仅持锁实例执行 `GenerationFlushScheduler` flush
- **现状**：`GenerationRegistry` 为进程内 `ConcurrentHashMap` 锁，**非** Redis 分布式锁

---

## 5. 模块改动

| 模块 | 改动 |
|------|------|
| `rag-service` | 3.4 全链路 |
| `orchestrator` | 3.2–3.3、3.7–3.11 |
| `skill-manager` | 3.11 新建 |
| `gateway` / `bff` / `sunshine-ui` | 3.2–3.3、3.12 |
| `scripts` / `docs/rag` | 3.4 评测 |

---

## 6. 检查门（19 条）

- [x] 3.4 v5 回归轨达标（hybrid+rerank 生产门禁 PASS）
- [ ] 3.4 v6 提升轨达标（生产门禁 PASS；**相对 vector +15% 轨 WARN**，见 closure 报告）
- [ ] 3.5 Grafana 远程 6 面板 + 4 告警可触发（指标 JSON ✅；部署 ⬜）
- [ ] 3.5 Sentinel Dashboard 租户 QPS
- [ ] 3.2 租户 A/B 隔离
- [ ] 3.3 写工具 HITL live（含子 Agent；代码 ✅）
- [x] 3.9 PLAN_WORKFLOW 三 API + Plan 详情/DAG 抽屉
- [x] 3.9 IntentRouter plan-workflow + Replan + 节点重试/降级/Recovery（`docs/routing/plan-workflow-retry-degradation.md`）
- [ ] **3.9.5** 暂停/续跑一致性（Planner stop、HITL/Recovery re-await、按钮分态、wfCtx 空拒绝）
- [ ] 3.10 2+ agent 节点 Plan live 演示（单测 ✅）
- [ ] 3.11 catalog + 3.12 `/skills` live
- [ ] 3.6 tool + sub_agent + plan.* 可查（API ✅）
- [ ] 3.7 Grounding 集成测试（代码 ✅）
- [ ] 3.10.7 子 Agent 不污染主 reasoning
- [ ] `phase2_agent_demo.py --suite all` 仍 PASS

*多实例：另验 3.14。3.8.2 PromptComposer 非检查门，周 6 交付。*

---

## 7. 下一步（2026-06-27）

**P0 收尾**
1. **3.9.5** 暂停/续跑一致性 → [pause-resume-consistency.md](../plans/2026-06-26-pause-resume-consistency.md)
2. **3.2** 跨租户集成测试 + `verify_tenant_live.py`
3. **3.3 / 3.5 / 3.11** live 验收脚本关检查门

**P1 生产**
4. **3.7** Grounding 集成测试
5. **3.5** Docker Prometheus scrape + `rule_files` 挂载告警
6. **3.14** Redis GenerationJob 分布式锁

**P2 并行**
7. **3.13** AhoCorasick 脱敏

**文档与计划**
- 生产加固 plan：`../plans/2026-06-19-phase3-production-hardening.md`
- 多 Agent / Skills：`../plans/2026-06-19-multi-agent-architecture.md`
- 覆盖度审计：`../plans/2026-06-20-phased-implementation-coverage.md`
- 下一阶段：[阶段四：平台化](./phase4-platformization-design.md)

历史详设：`2026-06-19-advanced-capabilities-design.md`、`2026-06-19-locked-architecture-decisions.md`
