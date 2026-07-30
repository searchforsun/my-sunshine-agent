# CLAUDE.md

Sunshine AI Platform — 企业级 AI 中台（AgentScope-Java + Spring Cloud Alibaba + Vue3/Naive UI）。

## 编码要义（最高优先级）

1. **两三轮仍不能解决 → 停补丁，查本质**：同一 bug 改 2–3 轮仍反复或出新症状，必须质疑**架构**、**时间线方案**与 **Catalog 提示词**的合理性；禁止继续打补丁式修改。
2. **找根因，简化设计**：优先从链路建模、SSE/步骤契约、提示词入手修正；方案要**简单**，禁止冗余分支与「兼容旧行为」的兜底逻辑（确需兼容须写明原因并评审通过）。
3. **模型输出不二次加工**：禁止对模型输出做截断、摘要或过滤兜底；不对就改 Catalog/`/prompts` 或架构，不在前后端打补丁。

**进度**：阶段三 **检查门通过** — 阶段四 **4.6 动态 DAG ✅** · **4.7 多专家协作 ✅** · **4.7.5 ReAct TaskBoard ✅** · **4.7.6 Spawn Subagent ✅**（含单独取消；Live：`verify_spawn_subagent_live`）· **4.8 工具集成 ✅** · **4.13 Workflow Studio ✅ 收口** · **4.5 沙箱方案 B ✅**（工作区抽屉 / `writeHitlMode` / 时间线路径 / **工具取消**，索引 `docs/sandbox/README.md`）；缺口见 `docs/implementation-plan.md`。

## 常用命令

编译、启动、验收命令见 [README.md](./README.md) §快速开始。改 `docs/nacos/*.yaml` 后必跑 `python scripts/sync_nacos.py` 并重启消费服务。

修改后端功能后必须重启对应服务start.py

**运维脚本（SSOT：`scripts/*.py`）**

| 脚本 | 用途 |
|------|------|
| `sunshine_lib.py` | 公共库（MySQL/Redis/启停 JVM） |
| `sync_nacos.py` | Nacos 配置同步 |
| `start.py` | 按依赖顺序启动全链路 |
| `clear_session_cache.py` | 清会话 + L1/L2/L3（MySQL+Milvus+Redis）；可选重启 |
| `download_skywalking_agent.py` | 下载 SkyWalking Agent |
| `phase2_agent_demo.py` | Phase 2.4 ReAct 验收；`--suite all\|react\|workflow\|react-taskboard` |
| `verify_execution_preference.py` | Chat 底栏 `executionPreference` 强制路由 §J Live 验收 |
| `rag_reset.py` | RAG Milvus 清库重建 |
| `rag_ingest_bulk.py` | 按 document 表 + `docs/knowledge/*.md` 批量入库（`--strategy`） |
| `verify_chunk_strategies_live.py` | 五策略分块 + publish 门禁 Live |
| `rag_eval.py` | corpus-50 评测（`--sync` 写 MySQL + Admin 跑门禁） |
| `generate_rag_corpus.py` | 生成 50 篇语料 + `eval_suite.json` |
| `rag_wipe_and_ingest.py` | 删文档 + 清库 + 批量 ingest |
| `verify_rewrite_timeline.py` | Timeline 改写 detail/metadata 验收 |
| `verify_skills_ui_live.py` | **3.12** `/skills` 管理页 API Live（列表/版本/diff/上传） |
| `verify_skill_5b_live.py` | **3.11** Skill 5B Chat `@` + Plan 触发 |
| `verify_hitl_live.py` | **3.3** HITL 写工具（`--live`） |
| `verify_personal_rules_live.py` | 个人规则（soul）注入 Live（P1–P5：设置/ReAct/Workflow 生效/清空/请求体不带不注入） |
| `verify_audit_live.py` | **3.6** 审计三 API |
| `verify_grafana_rag_live.py` | **3.5** Grafana RAG 可观测 |
| `verify_sentinel_dashboard.py` | **3.5** Sentinel Dashboard 联调 |
| `verify_tenant_qps_live.py` | **3.5** 租户 QPS 限流 burst |
| `verify_tenant_live.py` | **3.2** 多租户隔离（`--live`） |
| `verify_grounding.py` | **3.7** Grounding 单测 |
| `verify_subagent_timeline.py` | **3.10** workflow agent subSteps |
| `verify_pause_resume_consistency.py` | **3.9.5** 暂停/续跑（`--live`） |
| `verify_react_taskboard_live.py` | **4.7.5** ReAct TaskBoard §F Live（F1 + F-N1） |
| `verify_spawn_subagent_live.py` | **4.7.6** ReAct `spawn_subagent` Live（S1 hard + S4 soft；S5 单测） |
| `sync_enterprise_experts.py` | 企业业务分析专家文案/工具/skill 同步 Live（保留 id） |
| `verify_sandbox_live.py` | **4.5** Skills Docker 沙箱 Live（`--suite direct\|chat\|all`；G1–G12，含 `#sandbox-agent` S4） |
| `verify_sandbox_workspace_live.py` | **4.5** 对话级 Workspace 抽屉（W1–W5：status/SSE/list/content/复用） |
| `verify_sandbox_tool_cancel_live.py` | **4.5.7** 沙箱 exec/grep/glob 单工具取消（paused + 主消息 completed） |
| `verify_context_layers_live.py` | **上下文 L1/L2/L3** Admin + 单测门禁（SUB 空记忆 / 近窗排除 / GC） |
| `verify_dynamic_context_live.py` | **动态上下文压缩** 单测聚合 + Gateway `/v1/models` + 短对话不压缩（`--skip-live` 跳过 SSE） |
| `verify_observability_live.py` | **6.x 可观测性** 三台联动 Live（L1 指标/L2 Run 瀑布/L3 Kibana trace_id/L4 SkyWalking 业务 span/L5 Grafana） |

沙箱文档索引：[`docs/sandbox/README.md`](./docs/sandbox/README.md)。
| `verify_tool_integration_live.py` | **4.8** SDK+MCP 工具集成 Live（`--suite sdk\|mcp\|toolset\|hitl\|all`） |
| `verify_workflow_studio_live.py` | **4.13** Studio Catalog/`#`/`parallel`/`exclusive` Live |
| `verify_enterprise_workflow_live.py` | 企业流程 Live（`--suite read\|write\|all`；E1–E3 只读硬门 + E4–E6 写 HITL） |
| `verify_exclusive_gateway_live.py` | **4.13.7** exclusive-gateway 边条件（`#knowledge-branch`） |
| `verify_loop_live.py` | **4.13.7** loop do-while + subSteps（`#knowledge-loop`） |
| `verify_plan_dag_live.py` | **4.6** Plan-Workflow 动态 DAG（parallel/exclusive/loop Planner + 校验 + 布局） |
| `verify_prompt_catalog_live.py` | **4.11** Prompt Catalog Live（catalog / dry-run / priority / rollback；直连 `:8500`） |

> **提示词 / 路由规则 SSOT**：`prompt-manager` DB（`/prompts` + Catalog），**不再**经 Nacos `agent.routing.*` / 正文提示词；Nacos 仅保留非提示词运行参数与迁出占位注释。

## 请求链路与模块

架构图、端口、项目结构、中间件与**编译/启动/验收命令** SSOT 见 [README.md](./README.md)（§架构概览 · §项目结构 · §快速开始 · §服务器中间件）。

Agent 编排要点（扩展阅读，非运维重复）：`ChatController` → `ExecutionDispatcher` → `StreamToken` → `GenerationJob`（Redis 缓冲 + seq）→ BFF/Gateway 透传 → 前端 `parseSsePayload`。步骤事件 `type:step` / `type:step_delta` 由 `GenerationFlushScheduler.metaStep` 序列化。各服务 `application.yml` 仅 Nacos 入口；业务配置 SSOT 在 `docs/nacos/`。

## 架构与扩展（要点）

**原则**：注册 + Catalog 驱动，禁止 orchestrator/前端硬编码工具 Map。

| 要扩展 | 改哪里 |
|--------|--------|
| 新工具 | 业务 App 引入 `common/sunshine-tool-sdk` 声明 `@SunshineTool` → Nacos 注册（metadata `sunshine.tool-app=true`）→ `/tools` 启用 → 加入 ReAct 工具集；Workflow 节点 `params.tool` 填 **Catalog ID**（`sdk__{app}__{name}`）；**禁止** tool-manager 新增编译期 `ToolHandler` |
| 新 Workflow | **4.13**：`/workflows` + `workflow-manager` DB（**唯一 SSOT**，废弃 Nacos workflow）；MySQL init 种子 **8 标杆**（`13-sunshine-workflow-manager.sql`，含 `knowledge-branch` / `knowledge-loop` / `sandbox-agent`）；orchestrator `WorkflowManagerClient` |
| **静态 Workflow** | L2 规则命中 → `WorkflowExecutor`：`StaticPlanAdapter` 物化 Plan → `execution_plan` 落库 → 与 plan-workflow **同 UI**（`PlanWorkflowPanel` / `PlanExecutionCanvas`）；answer prompt 仍用 YAML 模板（不经 `PlanAnswerPromptAssembler`）--静态 workflow 的 answer prompt 是业务定制内容（含特定上游 `{{nodeId.output}}` 引用与流程约束），与 workflow 定义一体维护于 DB `plan_json`，非 Java 硬编码；动态 Plan 才走 Catalog `answer.template` |
| **Plan-Workflow** | 意图 L1/L3 → `PlanWorkflowExecutor`；Planner → `PlanValidator` → **Replan**（校验失败）→ **用户确认**（可选）→ 执行；节点 **`NodeRetryExecutor`** + `on-failure`；重试策略 SSOT **`execution_mode_policy`**（tool-manager DB，`/tools` Planner Workflow Tab）；规划/校验耗尽或 `fallback_react` → **ReAct**；详见 `docs/routing/plan-workflow-retry-degradation.md`、**用户确认** `docs/superpowers/specs/2026-06-27-plan-user-approval-design.md` |
| **Plan 终态 answer** | 引擎固定拼接 `id=answer`（Planner 勿输出，同 start）；`params.prompt` 由 Catalog **`answer.template`** + `PlanAnswerPromptAssembler` 注入 |
| Query 改写 | **检索域**（rag/hyde/empty-recall）→ `rag-service` `KnowledgeRetrievalPipeline`（[ADR-002](docs/architecture/ADR-002-rag-pipeline-in-rag-service.md)）；**路由域**（intent/planner）→ orchestrator `QueryRewriteService`；RAG 链：**rag 改写 → 首检 → HyDE → empty-recall**（均在 rag-service 一次 RPC） |
| **意图路由** | **Policy Chain**：L0 Skill → `UnifiedRuleRoutingPolicy`（Catalog `routing-rule.*`）→ L3 `intent.classifier`；验收见 `docs/routing/routing-golden-set.md` · Live `verify_prompt_catalog_live.py` |
| **Chat 执行模式** | 底栏 `executionPreference`（`auto` + `react` / `workflow` / `plan-workflow`）→ `ForcedExecutionRouter` 覆盖 L1–L3；**具体 workflow 模板**由 4.13 `#` + `workflow-manager` catalog，**不在底栏做二级下拉**；见 `2026-06-25-chat-execution-mode-selector-design.md` §1.1 · [remove-simple-llm](docs/superpowers/specs/2026-07-17-remove-simple-llm-mode-design.md) |
| **Workflow 模板（4.13）** | `workflow-manager` DB + `/workflows` + Chat `#` 补全；标杆维护见 `docs/workflow/README.md`；详设 `2026-06-25-workflow-studio-design.md` · `2026-07-11-workflow-studio.md` |
| Workflow 节点中文名 | PlanJson `displayName`（runtime bind）+ tool catalog → `WorkflowNodeLabelService` → SSE `step.label` |
| 意图步骤文案 | Catalog `timeline.intent`（before/active/after 模板）+ catalog 可选 `intentAfter`；**禁止**在 `StepSummarizer` 硬编码流程名 |
| 步骤 before/active/after | Catalog `timeline.steps.*`（plan / rag / generate 等）；前端 **只展示** SSE `summary` 当前阶段一行，勿写死步骤话术 |
| 步骤中文名（ReAct 工具） | tool-manager catalog → `ToolCatalogService` → SSE `step.label`；前端 **勿**维护 `TOOL_DISPLAY_NAMES` |
| 新 Agent 能力 / 子 Agent 配置 | `agent/runtime/` — 扩展 `AgentRunRequest` + `ReActAgentFactory`；workflow agent 节点 params 见 DB PlanJson / Studio |
| **ReAct TaskBoard（4.7.5）** | `manage_tasks` 元工具 + 唯一 `tasks` 步；Hook 跳过 manage_tasks tool 行、首建锚定 think；prompt 仅建板/status；merge 引擎去重；详设 `docs/superpowers/specs/2026-06-24-react-taskboard-design.md` |
| **ReAct Spawn Subagent（4.7.6）** | 元工具 `spawn_subagent`（仅 MAIN）；`AgentRuntime.run(SUB)` 上下文隔离；主卡 `subagent-*` + 抽屉 `spawnPrompt`/`subSteps`；**单独取消**（`SpawnRunRegistry`，勿 bump epoch）；支持 `agentId` 绑定具体 Agent；详设 `docs/superpowers/specs/2026-07-18-react-spawn-subagent-design.md` · Live `verify_spawn_subagent_live.py` |
| **沙箱工具取消（4.5.7）** | `sandbox__exec`/`grep`/`glob`：`CancellableToolRunRegistry` + sandbox kill；hover 圆钮；主行 **已取消**；同族预算 3；详设 `2026-07-18-sandbox-tool-cancel-design.md` · Live `verify_sandbox_tool_cancel_live.py` |

**Tool 链路**：`ToolRegistry` → `GET /api/tools/catalog` + `POST /api/tools/summarize-*` → orchestrator `ToolCatalogService` / `ToolManagerClient` → `DynamicToolkitFactory`（`RagTool` + `CatalogRemoteAgentTool`）→ `StepLabels`。Catalog ID SSOT：`ToolIds`（`sdk__*` / `mcp__*`）；ReAct LLM `tool_call.name` 与 Catalog 同 ID；静态 Workflow `tool` 节点直调 invoke（不经 LLM）。HITL 读 DB `require_confirmation`。ReAct 验收可查 llm-gateway 日志 `toolCalls=`。

**Agent 运行时（3.10.1–3.10.7 ✅）**：唯一入口 `AgentRuntime.run(AgentRunRequest)`；SUB 用 `AssembledContext.forSubAgent()`（无 L1/L2/L3）+ `skillId`→`PromptComposer`；skill overlay **仅** skill-manager Catalog（3.11 ✅）。

**子 Agent 目标（SSOT：`docs/superpowers/plans/2026-06-19-multi-agent-architecture.md` §子 Agent 实现目标）**：编排器-Worker；`query` + 上游 `context` 由 workflow 传入；system = base + skill overlay + 节点 `systemOverlay`；用户正文由下游 **answer** 节点合成。

**Prompt 拼装（3.8.2 ✅ / 4.11 Catalog）**：`PromptComposer` 6 层叠加 → `ReActAgentRuntime` / `AnswerNodeHandler`；SUB 的 `skillId` 走 skill overlay 层；ReAct 工具策略见 Catalog `mode-overlay.react`（`/prompts`）。

**Query 改写（3.8.1 ✅ → 4.0 迁移）**：检索侧 `rag.rewrite.{rag,hyde,empty-recall}` 迁入 `sunshine-rag.yaml` + pipeline；orchestrator 保留 `intent`（`<8` 字）| `planner`；HyDE 为 **首次 0 命中 fallback**；Timeline RAG 步骤读 response `trace`。

**RAG 检索策略**：orchestrator `rag.search.strategy` 透传 rag-service（默认 `hybrid+rerank`）；向量锚点门禁见 `RetrievalService`。

**可观测性（6.x 设计中）**：三台联动以 `traceId` 贯穿--logging 经 Filebeat 进 ES（logback `%tid` 注入 SkyWalking traceId）+ Kibana Index Pattern；metrics 全服务 Prometheus + LLM 指标（`LlmMetricsRecorder`：`llm_call_duration_seconds`/`llm_tokens_total`/`llm_tool_calls_total`/`llm_fallback_total`）+ Grafana 面板；trace 业务 span（`@Trace`：`orchestrator.execution`/`agent.run`/`react.loop`/`workflow.node`/`tool.invoke`/`rag.search`）+ SSE 首事件 traceId；前端 `/observability` LangSmith 式 Run Explorer（echarts 瀑布图 + 三台外链）；详设 `2026-07-27-observability-enhancement-design.md`。**复用** 5.1/5.2/5.3 落库，不重复建表。

### 时间线（ReAct vs Workflow DAG）

| 模式 | 步骤形态 | 说明 |
|------|----------|------|
| **ReAct** | `intent → think → tasks? → tool → think-2 → generate` | TaskBoard 开启时出现 `tasks`（锚定在 think 后）；**无业务 tool 间隔**的连续 reasoning 合并为同一 think（Hook），避免终态堆叠多个「综合分析」 |
| **ReAct spawn_subagent** | 主时间线 `subagent-{runId}`（`phase=subagent`） | 元工具委派；子 think/tool 仅在 `subSteps`；主卡一行摘要 + 抽屉（`spawnPrompt`）；用户取消 → `paused` +「已取消」；详设 `2026-07-18-react-spawn-subagent-design.md` |
| **沙箱可取消工具** | `tool-sandbox__{exec\|grep\|glob}@…` | 用户取消 → `lifecycle=paused`，`summary.after=已取消`，detail 保留 command/pattern 供展开；详设 `2026-07-18-sandbox-tool-cancel-design.md` |
| **静态 Workflow** | `intent → plan → …`（DAG） | `WorkflowExecutor`：`StaticPlanAdapter` + `PlanTimeline`（`planId=`）→ `executeDynamicDefinition`；**无**逐步 `OperationCard` |
| **Plan-Workflow** | `intent → plan → …`（DAG） | `PlanWorkflowExecutor` + Planner JSON；**成功路径无 `think`/`generate`**；与静态 workflow **共用** `PlanWorkflowPanel` / `PlanNodeDrawer` |
| **Workflow agent 节点** | 主时间线仅 `node-{id}` 一步 | 子 Agent 内部 think/tool **不上主时间线**；`AgentNodeDetailSummarizer` 供主行 after + 展开 detail |
| **Workflow loop 容器** | 主时间线仅 `node-{loopId}` | body 多轮 → `subSteps`（id=`i{n}-node-…`）；**禁止** body 节点上主时间线 |
| **Workflow / Plan answer 节点** | 主时间线 `node-answer` + `step_delta(result)` | 仅 `step_delta(result)` SSOT（勿双写 content）；空白 token 勿 `hasText`/`isBlank` 过滤 |
| **Plan/Workflow agent 节点** | 子 Timeline + `contentBlocks` | ReAct 正文经 `ingestStreamingContentDelta` → 分段 SSE；**禁止** `isBlank` 丢弃空白 delta |
| **Synthesizer 终态正文** | Hub 后 `message.content` 流式 | `ConsultationSynthesizer` → `LlmGatewayClient` → `StreamDeltaNormalizer`（闭合 `**` 等短 token 勿按前缀回退丢弃，TD-076）；**无** `generate` Timeline 步 |

**reasoning 落点（勿双写）**

| 路径 | SSE / steps | message.reasoning | 前端合成 think |
|------|-------------|-------------------|----------------|
| ReAct | `think*` step | 可选（generate 路径） | `normalizeTimelineSteps` 可合成 |
| Plan/Workflow `node-*` | 挂在对应 node step | **不写**（`GenerationJob` + `chatSessions`） | **不合成**（有 plan/node 即跳过） |

**Plan 节点抽屉**（`PlanNodeDrawer`）：answer/llm → **综合分析**（`step.reasoning` 原样）+ **最终输出**（`step.result` 原样）；业务节点可展开 **执行记录**（`attempts[]`）；RAG 节点 `metadata.rewriteInDetail=true` 时改写 trace 进抽屉 **检索过程**（`expandSectionTitle`），前端勿关键字过滤；无「执行摘要」；长文随 `.drawer-body` 整体滚动（区块内无嵌套滚动条）。

**Timeline V2 约定**：步骤含 `lifecycle` + `summary.{before,active,after}`；SSE 仅下发当前阶段一行。终态 COMPLETE/FAIL/SKIP **必须下发**。

**前端**：`OperationStack` / `PlanExecutionCanvas` / `PlanNodeDrawer` / `PlanApprovalActions`；时间线主行用 `step.label` + `resolveStepHeaderText`；**Plan 用户确认**折叠框与 HITL/Recovery 同组件；重新生成 **仅图区** loading、确认行「正在重新生成」、放大钮右上角且重生成中隐藏；DAG pending **等待中**；**勿**维护本地步骤话术 Map；**勿**对模型输出做截断/去重兜底（不对改 Catalog/`/prompts`）。

## 关键约定

1. OpenAIChatModel 对接 Gateway `/v1/chat/completions`。
2. Gateway 鉴权注入 `x-user-id`；BFF/Orchestrator 只读，客户端不得自填。
3. Nacos SSOT：改 `docs/nacos/*.yaml` → `sync_nacos.py` → 重启（无 `application-dev.yaml`）。
4. 执行模式：`IntentRouter` → `ExecutionDispatcher`（`workflow` / `react` / `plan-workflow`）；`simple-llm` 已移除见 `2026-07-17-remove-simple-llm-mode-design.md`）；workflow 图在 **workflow-manager DB**（4.13）。
5. 财务/react 工具经 tool-manager；**禁止** Controller 拼 prompt 模板（见 Catalog `system-prompt` / `/prompts`）。
6. `ChatCompletionResponse` 用 `@Builder` 须加 `@NoArgsConstructor` + `@AllArgsConstructor`。
7. 审计：assistant 终态 → RocketMQ / MySQL / ES；`GET /api/audit/recent`。
8. Workflow 意图步：`summary.after` 保留路由文案（如「将按 xx 流程处理」）；`detail` 不下发，避免与 after 重复可展开。
9. ReAct / workflow agent 节点统一经 `AgentRuntime.run(AgentRunRequest)`；禁止新增兼容门面或绕过 `AgentRunRequest` 直接调 ReActAgent。

## 中间件（ecs4c16g）

端口与凭据见 [README.md](./README.md) §服务器中间件（ecs4c16g）。

## 版本与前端

- 勿升 Spring Boot 3.3+；Sa-Token **1.45.0**（需 `sa-token-jwt`）。**AgentScope 已升 2.0**（native-first，P0-P3 完成；P4-P6 经 E5 评审不迁移，spawn/沙箱/HITL/peer 保留全栈自研）。
- ReAct 续跑/checkpoint 依赖 **Redis StateStore TTL=7d**（`agentscope:state:` 前缀）；`disableSessionPersistence()` 自 2.0 起为 no-op，自动持久化 + JVM 优雅停机（`GracefulShutdownManager`）由官方接管，勿自研 ShutdownHook。
- SSE 基址：生产构建须设 `VITE_BFF_STREAM_BASE`（见 `sunshine-ui/.env.production.example`）；开发态走 Vite proxy。
- 思考区字号：`OperationCard` / `ReasoningPanel` 用 `--sun-font-base`（14px）。

### UI 风格（Codex 简约）

**SSOT**：`global.css`（`--sun-*`）、`markdown-content.css`（`--smd-*`）、`mermaidConfig.ts`、`useTheme.ts`（hljs）。

**原则**：背景统一 **`--sun-black`（= `--sun-bg` = `--sun-sidebar-bg`）**，**边框**分区；**禁止**页面/面板/输入/选中态用 `--sun-surface` / `--sun-deep` / `--sun-accent-muted` 灰底。

| 元素 | 规则 |
|------|------|
| 页面、卡片、composer、输入框 | `--sun-black` 底 + `1px var(--sun-border)`；focus 无 shadow |
| 块头栏、Plan 确认框、预览顶栏 | `transparent` 底，保边框 |
| 下拉选中 | 对号 **18px**、无灰底；compact 宽 **304px**、说明不换行（见 `ExecutionModeSelector` / `TenantSelector`） |
| 卡片/DAG 选中 | 内描边或 ring，hover 仅改边框（见 `SkillsView` / `PlanExecutionCanvas`） |
| 文件树选中 | active 背景 transparent + 文字加粗 |
| 代码/Mermaid | `--smd-block-bg` = 正文色；hljs/Mermaid 主题走 `useTheme` / `mermaidConfig`（`theme: 'base'`）；复制用 `stream-markdown/clipboard.ts` |
| 功能参数说明 | 禁止添加冗余描述，详细说明添加到？说明中 |
**已对齐**：Chat、Plan 组件、Skills / **Experts** / Knowledge / Status 页、MainLayout 侧栏。

**Experts `/experts`**：与 Skills 同构（左列表 + 右详情）；`--sun-black` 底 + 边框分区，输入/下拉用 `sun-field` 覆写 Naive 灰底；新建弹窗仅 ID+展示名，启用开关在左卡（必填保存后）。

## 其他

- 架构决策（ADR）：[docs/architecture/README.md](./docs/architecture/README.md)
- 代码加适量中文注释；**禁止**在业务代码中插入多余空行。
- 禁止保存临时脚本；运维统一 **Python**（`scripts/*.py`）。
- `start.py` 可带 SkyWalking agent（需先 `download_skywalking_agent.py`）。
- 改 orchestrator 时间线 / workflow 后：编译 → 重启 → Agent 跑 live/e2e 留记录（见 `/tech-debt-refactor` §7）；**改前须 §1.3 功能识别并获确认**。
- 项目中禁止硬编码提示词；正文 SSOT = prompt-manager Catalog（`/prompts`）；Nacos 仅保留非提示词运行参数
- **禁止 Flyway**；库表初始化/变更 SQL SSOT 在 `docker/mysql/init/`（`01` 建库 + `02–05` 中间件 + `10` auth / `11` orchestrator / `12` skill-manager / `13` workflow-manager / `14` rag-service，**一项目一文件**），**禁止**放在各模块 `resources/db/migration`
