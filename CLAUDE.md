# CLAUDE.md

Sunshine AI Platform — 企业级 AI 中台（AgentScope-Java + Spring Cloud Alibaba + Vue3/Naive UI）。

**进度**：阶段三 **检查门基本通过**（live 脚本全绿；v6 相对 vector +15% 仍 WARN）— 3.4 RAG、3.8、3.9 PLAN_WORKFLOW、3.10 AgentRuntime、3.11 skill-manager、**3.12 `/skills` live ✅**、3.6 审计、3.2/3.3/3.5/3.7 live ✅、3.9.5、3.13/3.14 ✅；阶段四 **4.7.3 PEER_COLLAB** / **4.7.5 TaskBoard** ⬜；缺口见 `docs/implementation-plan.md` 与 `docs/superpowers/specs/phase3-production-hardening-design.md` §6。

## 常用命令

编译、启动、验收命令见 [README.md](./README.md) §快速开始。改 `docs/nacos/*.yaml` 后必跑 `python scripts/sync_nacos.py` 并重启消费服务。

**运维脚本（SSOT：`scripts/*.py`）**

| 脚本 | 用途 |
|------|------|
| `sunshine_lib.py` | 公共库（MySQL/Redis/启停 JVM） |
| `sync_nacos.py` | Nacos 配置同步 |
| `start.py` | 按依赖顺序启动全链路 |
| `clear_session_cache.py` | 清会话 + 可选重启 |
| `download_skywalking_agent.py` | 下载 SkyWalking Agent |
| `phase2_agent_demo.py` | Phase 2.4 ReAct 验收；`--suite all\|react\|workflow` |
| `verify_execution_preference.py` | Chat 底栏 `executionPreference` 强制路由 §J Live 验收 |
| `rag_reset.py` | RAG Milvus 清库重建 |
| `rag_ingest_bulk.py` | 按 golden-set 批量入库 |
| `rag_eval.py` | RAG Recall/MRR 基线评测 |
| `verify_rewrite_timeline.py` | Timeline 改写 detail/metadata 验收 |
| `verify_skills_ui_live.py` | **3.12** `/skills` 管理页 API Live（列表/版本/diff/上传） |
| `verify_skill_5b_live.py` | **3.11** Skill 5B Chat `@` + Plan 触发 |
| `verify_hitl_live.py` | **3.3** HITL 写工具（`--live`） |
| `verify_audit_live.py` | **3.6** 审计三 API |
| `verify_grafana_rag_live.py` | **3.5** Grafana RAG 可观测 |
| `verify_sentinel_dashboard.py` | **3.5** Sentinel Dashboard 联调 |
| `verify_tenant_qps_live.py` | **3.5** 租户 QPS 限流 burst |
| `verify_tenant_live.py` | **3.2** 多租户隔离（`--live`） |
| `verify_grounding.py` | **3.7** Grounding 单测 |
| `verify_subagent_timeline.py` | **3.10** workflow agent subSteps |
| `verify_pause_resume_consistency.py` | **3.9.5** 暂停/续跑（`--live`） |

目录内遗留 `.ps1`/`.sh` 为历史包装，**勿再维护**；新脚本一律 Python。

## 请求链路与模块

```
Browser → Gateway :8000 [JWT] → BFF :8001 → Orchestrator :8200
  ├─ simple-llm / workflow(DAG) / react(ReActAgent)
  ├─ llm-gateway :8300  rag :8400  tool-manager :8210 → finance :8710
  └─ desensitize :8600
```

| 模块 | 端口 | 职责 |
|------|:----:|------|
| gateway / bff / auth-center | 8000/8001/8100 | 路由、SSE 透传、JWT |
| orchestrator | 8200 | 三模式 + Timeline + GenerationJob + `AgentRuntime` |
| tool-manager | 8210 | `ToolRegistry` + `/api/tools/catalog` |
| llm-gateway / rag-service | 8300/8400 | 模型路由 / Milvus |

各服务 `application.yml` 仅 Nacos 入口；业务配置 SSOT 在 `docs/nacos/`。

**SSE 链路**：`ChatController` → `ExecutionDispatcher` → `StreamToken` → `GenerationJob`（Redis 缓冲 + seq）→ BFF/Gateway 透传 → 前端 `parseSsePayload`。步骤事件 `type:step` / `type:step_delta` 由 `GenerationFlushScheduler.metaStep` 序列化。

## 架构与扩展（要点）

**原则**：注册 + Catalog 驱动，禁止 orchestrator/前端硬编码工具 Map。

| 要扩展 | 改哪里 |
|--------|--------|
| 新工具 | `tool-manager` 新增 `ToolHandler`（含 displayName / timelinePhase / outputSummaryKind）→ Nacos `agent.execution.react.tools` 或 workflow 节点 `params.tool` → sync + 重启 tool-manager、orchestrator |
| 新 Workflow | **当前**：`docs/nacos/sunshine-workflows.yaml` catalog + definitions → sync → 重启 orchestrator；**4.13**：`/workflows` + `workflow-manager` DB 发布（同 ID 覆盖 Nacos） |
| **静态 Workflow** | L2 规则命中 → `WorkflowExecutor`：`StaticPlanAdapter` 物化 Plan → `execution_plan` 落库 → 与 plan-workflow **同 UI**（`PlanWorkflowPanel` / `PlanDagGraph`）；answer prompt 仍用 YAML 模板（不经 `PlanAnswerPromptAssembler`） |
| **Plan-Workflow** | 意图 L1/L3 → `PlanWorkflowExecutor`；Planner → `PlanValidator` → **Replan**（校验失败）→ **用户确认**（可选）→ 执行；节点 **`NodeRetryExecutor`** + `on-failure`；规划/校验耗尽或 `fallback_react` → **ReAct**；详见 `docs/routing/plan-workflow-retry-degradation.md`、**用户确认** `docs/superpowers/specs/2026-06-27-plan-user-approval-design.md` |
| **Plan 终态 answer** | 引擎固定拼接 `id=answer`（Planner 勿输出，同 start）；`params.prompt` 由 **`agent.prompt.answer-template`** + `PlanAnswerPromptAssembler` 注入 |
| Query 改写 | `agent.rewrite.{rag,intent,empty-recall}`（**默认开启**，flash）→ `QueryRewriteService` + `KnowledgeRetrievalService` + `ExecutionPlanRouter`；RAG 链：**rag 改写 → 首次检索 →（0 命中）HyDE fallback → empty-recall** |
| **意图路由** | **Policy Chain**：L0 Skill → L1 `agent.routing.structural` → L2 `agent.routing.rules` → L3 `agent.intent`；验收见 `docs/routing/routing-golden-set.md` |
| **Chat 执行模式** | 底栏 `executionPreference`（五模式）→ `ForcedExecutionRouter` 覆盖 L1–L3；**具体 workflow 模板**由 4.13 `#` + `workflow-manager` catalog，**不在底栏做二级下拉**；见 `2026-06-25-chat-execution-mode-selector-design.md` §1.1 |
| **Workflow 模板（4.13）** | `workflow-manager` + `/workflows` + Chat `#` 补全；Nacos 内置 + DB 合并 `CompositeWorkflowCatalog`；见 `2026-06-25-workflow-studio-design.md` |
| Workflow 节点中文名 | `sunshine-workflows.yaml` 节点 `displayName` → `WorkflowNodeLabelService` → SSE `step.label` |
| 意图步骤文案 | Nacos `agent.timeline.intent`（before/active/after 模板）+ catalog 可选 `intentAfter`；**禁止**在 `StepSummarizer` 硬编码流程名 |
| 步骤 before/active/after | Nacos `agent.timeline.steps`（plan / rag / generate 等）；前端 **只展示** SSE `summary` 当前阶段一行，勿写死步骤话术 |
| 步骤中文名（ReAct 工具） | tool-manager catalog → `ToolCatalogService` → SSE `step.label`；前端 **勿**维护 `TOOL_DISPLAY_NAMES` |
| 新 Agent 能力 / 子 Agent 配置 | `agent/runtime/` — 扩展 `AgentRunRequest` + `ReActAgentFactory`；workflow agent 节点 params 见 `sunshine-workflows.yaml` |

**Tool 链路**：`ToolRegistry` → `GET /api/tools/catalog` → orchestrator `ToolCatalogService` → `DynamicToolkitFactory`（`RagTool` + `CatalogRemoteAgentTool`）→ `StepLabels` / `ToolResultSummarizer`。

**Agent 运行时（3.10.1–3.10.7 ✅）**：唯一入口 `AgentRuntime.run(AgentRunRequest)`；SUB 用 `MemoryContext.forSubAgent()`（无 STM/LTM）+ `skillId`→`PromptComposer`；skill overlay 优先 **skill-manager Catalog**（3.11 ✅），Nacos `agent.prompt.skill-overlays` 兜底。

**子 Agent 目标（SSOT：`docs/superpowers/plans/2026-06-19-multi-agent-architecture.md` §子 Agent 实现目标）**：编排器-Worker；`query` + 上游 `context` 由 workflow 传入；system = base + skill overlay + 节点 `systemOverlay`；用户正文由下游 **answer** 节点合成。

**Prompt 拼装（3.8.2 ✅）**：`PromptComposer` 6 层叠加 → `ReActAgentRuntime` / `AnswerNodeHandler`；SUB 的 `skillId` 走 skill overlay 层；ReAct 工具策略见 Nacos `agent.prompt.mode-overlays.react`。

**Query 改写（3.8.1 ✅）**：`rag` | `intent`（`<8` 字）| `empty-recall`；HyDE 为 **首次 0 命中 fallback**（`agent.rewrite.rag.hyde.enabled`）；日志 `[QueryRewrite]`。

**RAG 检索策略**：orchestrator `rag.search.strategy` 透传 rag-service（默认 `hybrid+rerank`）；向量锚点门禁见 `RetrievalService`。

### 时间线（ReAct vs Workflow DAG）

| 模式 | 步骤形态 | 说明 |
|------|----------|------|
| **ReAct** | `intent → think → tool → think-2 → generate` | `ReactExecutor` → `AgentRuntime`；reasoning 走 `step_delta(think*)`；Hook 经 `StepEventBridge` 绑定 assistantMsgId |
| **静态 Workflow** | `intent → plan → …`（DAG） | `WorkflowExecutor`：`StaticPlanAdapter` + `PlanTimeline`（`planId=`）→ `executeDynamicDefinition`；**无**逐步 `OperationCard` |
| **Plan-Workflow** | `intent → plan → …`（DAG） | `PlanWorkflowExecutor` + Planner JSON；**成功路径无 `think`/`generate`**；与静态 workflow **共用** `PlanWorkflowPanel` / `PlanNodeDrawer` |
| **Workflow agent 节点** | 主时间线仅 `node-{id}` 一步 | 子 Agent 内部 think/tool **不上主时间线**；`AgentNodeDetailSummarizer` 供主行 after + 展开 detail |
| **Workflow / Plan answer 节点** | 主时间线 `node-{id}` 一步 | `WorkflowLlmStreamSupport`：reasoning → `step_delta(node-*, reasoning)`；content → 消息正文 + `step.result`；主行 after = **displayName+「完成」**（非模型正文） |

**reasoning 落点（勿双写）**

| 路径 | SSE / steps | message.reasoning | 前端合成 think |
|------|-------------|-------------------|----------------|
| ReAct | `think*` step | 可选（generate 路径） | `normalizeTimelineSteps` 可合成 |
| Plan/Workflow `node-*` | 挂在对应 node step | **不写**（`GenerationJob` + `chatSessions`） | **不合成**（有 plan/node 即跳过） |

**Plan 节点抽屉**（`PlanNodeDrawer`）：answer/llm → **综合分析**（`step.reasoning` 原样）+ **最终输出**（`step.result` 原样）；业务节点可展开 **执行记录**（`attempts[]`）；RAG 节点 `metadata.rewriteInDetail=true` 时改写 trace 进抽屉 **检索过程**（`expandSectionTitle`），前端勿关键字过滤；无「执行摘要」；长文随 `.drawer-body` 整体滚动（区块内无嵌套滚动条）。

**Timeline V2 约定**：步骤含 `lifecycle` + `summary.{before,active,after}`；SSE 仅下发当前阶段一行。终态 COMPLETE/FAIL/SKIP **必须下发**。

**前端**：`OperationStack` / `PlanDagGraph` / `PlanNodeDrawer` / `PlanApprovalActions`；时间线主行用 `step.label` + `resolveStepHeaderText`；**Plan 用户确认**折叠框与 HITL/Recovery 同组件；重新生成 **仅图区** loading、确认行「正在重新生成」、放大钮右上角且重生成中隐藏；DAG pending **等待中**；**勿**维护本地步骤话术 Map；**勿**对模型输出做截断/去重兜底（不对改 Nacos 提示词）。

## 关键约定

1. OpenAIChatModel 对接 Gateway `/v1/chat/completions`。
2. Gateway 鉴权注入 `x-user-id`；BFF/Orchestrator 只读，客户端不得自填。
3. Nacos SSOT：改 `docs/nacos/*.yaml` → `sync_nacos.py` → 重启（无 `application-dev.yaml`）。
4. 四模式（阶段四增第五）：`IntentRouter` → `ExecutionDispatcher`（`simple-llm` / `workflow` / `react` / `plan-workflow`；阶段四 **`peer-collab`** 见 D10 + `2026-06-24-peer-collab-routing-design.md`）；workflow 图在 `sunshine-workflows.yaml`。
5. 财务/react 工具经 tool-manager；**禁止** Controller 拼 prompt 模板（见 Nacos `agent.system-prompt`）。
6. `ChatCompletionResponse` 用 `@Builder` 须加 `@NoArgsConstructor` + `@AllArgsConstructor`。
7. 审计：assistant 终态 → RocketMQ / MySQL / ES；`GET /api/audit/recent`。
8. Workflow 意图步：`summary.after` 保留路由文案（如「将按 xx 流程处理」）；`detail` 不下发，避免与 after 重复可展开。
9. ReAct / workflow agent 节点统一经 `AgentRuntime.run(AgentRunRequest)`；禁止新增兼容门面或绕过 `AgentRunRequest` 直接调 ReActAgent。

## 中间件（ecs4c16g）

Nacos 8848 | MySQL 3306 root/root123 | Redis 6379 | Milvus 19530 | RocketMQ 9876 | ES 9200 | SkyWalking 11800/8084

## 版本与前端

- 勿升 Spring Boot 3.3+、AgentScope 2.0.0；Sa-Token **1.45.0**（需 `sa-token-jwt`）。
- UI：**Codex 中性灰**（`#212121` 系）；设计令牌 `src/styles/global.css`；字号阶梯保留（14/15/16）。
- SSE 基址：生产构建须设 `VITE_BFF_STREAM_BASE`（见 `sunshine-ui/.env.production.example`）；开发态走 Vite proxy。
- 思考区字号：`OperationCard` / `ReasoningPanel` 用 `--sun-font-base`（14px）。

## 其他

- 架构决策（ADR）：[docs/architecture/README.md](./docs/architecture/README.md)
- 禁止对模型输出内容做冗余的截断和摘要，模型返回什么就输出什么，不对就改提示词
- 禁止冗余的兜底和兼容逻辑，直接在架构和提示词方面优化，合理兼容兜底逻辑要给出原因，并评审通过
- 代码加适量中文注释；**禁止**在业务代码中插入多余空行。
- 禁止保存临时脚本；运维统一 **Python**（`scripts/*.py`）。
- `start.py` 可带 SkyWalking agent（需先 `download_skywalking_agent.py`）。
- 改 orchestrator 时间线 / workflow 后：编译 → 重启 → Agent 跑 live/e2e 留记录（见 `/tech-debt-refactor` §7）；**改前须 §1.3 功能识别并获确认**。
- 项目中禁止硬编码提示词等，统一在nacos管理
