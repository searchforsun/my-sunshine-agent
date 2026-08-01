# CLAUDE.md

Sunshine AI Platform — 企业级 AI 中台（AgentScope-Java + Spring Cloud Alibaba + Vue3/Naive UI）。

## 编码要义（最高优先级）

1. **两三轮仍不能解决 → 停补丁，查本质**：同一 bug 改 2–3 轮仍反复或出新症状，必须质疑**架构**与 **Catalog 提示词**的合理性；禁止继续打补丁式修改。
2. **找根因，简化设计**：优先从链路建模、SSE/步骤契约、提示词入手修正；方案要**简单**，禁止冗余分支与「兼容旧行为」的兜底逻辑。
3. **模型输出不二次加工**：禁止对模型输出做截断、摘要或过滤兜底；不对就改 Catalog/`/prompts` 或架构。

**进度**：阶段三 ✅ — 阶段四 **4.6 动态 DAG ✅** · **4.7 多专家协作 ✅** · **4.7.5 ReAct TaskBoard ✅** · **4.7.6 Spawn Subagent ✅** · **4.8 工具集成 ✅** · **4.13 Workflow Studio ✅** · **4.5 沙箱方案 B ✅** · **4.11 Prompt Catalog ✅** · **4.13.8 结构化 I/O ✅**；缺口见 `docs/implementation-plan.md`。

## 常用命令

编译、启动、验收命令见 [README.md](./README.md) §快速开始。改 `docs/nacos/*.yaml` 后必跑 `python scripts/sync_nacos.py` 并重启消费服务。修改后端功能后必须重启对应服务的 `start.py`。

**运维脚本（SSOT：`scripts/*.py`）**

| 类别 | 核心脚本 |
|------|----------|
| 启停/同步 | `start.py`、`sync_nacos.py`、`sunshine_lib.py` |
| RAG | `rag_reset.py`、`rag_ingest_bulk.py`、`rag_eval.py`、`rag_wipe_and_ingest.py`、`generate_rag_corpus.py`、`verify_chunk_strategies_live.py` |
| 阶段三验收 | `verify_tenant_live.py`、`verify_hitl_live.py`、`verify_skills_ui_live.py`、`verify_pause_resume_consistency.py` |
| 阶段四验收 | `verify_sandbox_live.py`、`verify_sandbox_workspace_live.py`、`verify_sandbox_tool_cancel_live.py`、`verify_spawn_subagent_live.py`、`verify_react_taskboard_live.py`、`verify_tool_integration_live.py`、`verify_workflow_studio_live.py`、`verify_plan_dag_live.py`、`verify_prompt_catalog_live.py`、`verify_enterprise_workflow_live.py`、`verify_loop_live.py`、`verify_exclusive_gateway_live.py`、`verify_personal_rules_live.py` |
| 其他 | `clear_session_cache.py`、`download_skywalking_agent.py`、`sync_enterprise_experts.py` |

> **提示词 / 路由规则 SSOT**：`prompt-manager` DB（`/prompts` + Catalog），**不再**经 Nacos `agent.routing.*`。

## 服务端口

| 服务 | 端口 | 说明 |
|------|:---:|------|
| `sunshine-ui` | 5173 | 前端 WebUI（Vue3 + Naive UI） |
| `gateway` | 8000 | Spring Cloud Gateway + Sentinel（统一入口） |
| `bff` | 8001 | WebFlux + SSE 流式转发 |
| `auth-center` | 8100 | Sa-Token 认证中心 |
| `orchestrator` | 8200 | 核心编排（workflow / react / plan-workflow / peer-collab） |
| `tool-manager` | 8210 | 工具注册与调用（SDK + MCP） |
| `skill-manager` | 8225 | Skills 上传 / 版本 / Catalog |
| `expert-manager` | 8235 | Expert CRUD / Catalog |
| `llm-gateway` | 8300 | LLM 网关（多厂商路由 / 缓存 / 熔断） |
| `rag-service` | 8400 | RAG 检索（Milvus + Hybrid + Rerank） |
| `prompt-manager` | 8500 | 提示词管理（`/prompts` + Catalog） |
| `desensitize` | 8600 | 数据脱敏 |
| `oa-service` | 8700 | OA 模拟 |
| `finance-service` | 8710 | 财务模拟 |
| `hr-biz-service` | 8720 | 人事模拟 |

**中间件**：Nacos `8848/9848` · MySQL `3306` · Redis `6379`（凭据见 [README.md](./README.md) §服务器中间件）。

## 请求链路

Agent 编排要点：`ChatController` → `ExecutionDispatcher` → `StreamToken` → `GenerationJob`（Redis 缓冲 + seq）→ BFF/Gateway 透传 → 前端 `parseSsePayload`。

## 架构与扩展（要点）

**原则**：注册 + Catalog 驱动，禁止 orchestrator/前端硬编码工具 Map。

| 要扩展 | 改哪里 |
|--------|--------|
| 新工具 | 业务 App 引入 `common/sunshine-tool-sdk` 声明 `@SunshineTool` → Nacos 注册 → `/tools` 启用；Workflow 节点 `params.tool` 填 Catalog ID（`sdk__{app}__{name}`） |
| 新 Workflow | `/workflows` + `workflow-manager` DB（唯一 SSOT）+ MySQL init 种子；orchestrator `WorkflowManagerClient` |
| **静态 Workflow** | L2 规则命中 → `WorkflowExecutor` → `StaticPlanAdapter`→ 与 plan-workflow **同 UI**（`PlanWorkflowPanel`）；answer prompt 随 workflow 定义维护于 DB `plan_json` |
| **Plan-Workflow** | `PlanWorkflowExecutor`：Planner → `PlanValidator` → **Replan** → **用户确认**（可选）→ 执行；`NodeRetryExecutor` + `on-failure`；重试策略 `execution_mode_policy`（tool-manager DB）；耗尽降级 ReAct；详见 `docs/routing/plan-workflow-retry-degradation.md` |
| **意图路由** | Policy Chain：L0 Skill → `UnifiedRuleRoutingPolicy`（Catalog `routing-rule.*`）→ L3 `intent.classifier` |
| **Chat 执行模式** | 底栏 `executionPreference`（`auto`/`react`/`workflow`/`plan-workflow`）→ `ForcedExecutionRouter`；workflow 模板用 4.13 `#` 补全 |
| **ReAct TaskBoard（4.7.5）** | 元工具 `manage_tasks` + 唯一 `tasks` 步；merge 引擎去重 |
| **ReAct Spawn Subagent（4.7.6）** | 元工具 `spawn_subagent`（仅 MAIN）；上下文隔离；`subagent-*` 卡 + 抽屉；**单独取消**（`SpawnRunRegistry`）；支持 `agentId` |
| **沙箱工具取消（4.5.7）** | `sandbox__exec`/`grep`/`glob`：`CancellableToolRunRegistry` + sandbox kill；同族预算 3 |

**Agent 运行时**：唯一入口 `AgentRuntime.run(AgentRunRequest)`；SUB 用 `AssembledContext.forSubAgent()`（无 L1/L2/L3）+ `skillId`→`PromptComposer`；禁止绕过 `AgentRunRequest`。

**Tool 链路**：`ToolRegistry` → orchestrator `ToolCatalogService` → `DynamicToolkitFactory`；Catalog ID SSOT：`sdk__*` / `mcp__*`；HITL 读 DB `require_confirmation`。

**Prompt 拼装**：`PromptComposer` 6 层叠加；ReAct 工具策略见 Catalog `mode-overlay.react`（`/prompts`）。

**Query 改写**：检索域 → `rag-service` `KnowledgeRetrievalPipeline`（[ADR-002](docs/architecture/ADR-002-rag-pipeline-in-rag-service.md)）；路由域 → orchestrator `QueryRewriteService`。

**RAG 检索策略**：orchestrator `rag.search.strategy` 透传 rag-service（默认 `hybrid+rerank`）。

**可观测性（6.x 设计中）**：三台联动以 `traceId` 贯穿；详设 `docs/superpowers/specs/2026-07-27-observability-enhancement-design.md`。

## 时间线要点

| 模式 | 关键约束 |
|------|----------|
| **ReAct** | `intent → think → tasks? → tool → think-2 → generate`；连续 reasoning 合并为同一 think；SSE 仅下发当前阶段一行 |
| **ReAct spawn_subagent** | 主卡 `subagent-{runId}` + 抽屉 `subSteps`；取消 → `paused` +「已取消」 |
| **Workflow（静态/Plan）** | 主时间线 `intent → plan → …`（DAG）；agent 节点内部不上主时间线；loop body 进 `subSteps`；answer 节点仅 `step_delta(result)`，勿双写 content |
| **沙箱工具** | 取消 → `lifecycle=paused`，`summary.after=已取消` |
| **Synthesizer** | Hub 后 `message.content` 流式；**无** `generate` Timeline 步 |

**reasoning 落点**：ReAct `think*` step、Plan/Workflow `node-*` reasoning 挂 node step、Plan 不合成 think。

**Plan 节点抽屉**（`PlanNodeDrawer`）：answer/llm → **综合分析** + **最终输出**（原样）；业务节点 **执行记录**（`attempts[]`）；RAG 改写 trace 进抽屉 **检索过程**。

**前端**：`OperationStack` / `PlanExecutionCanvas` / `PlanNodeDrawer` / `PlanApprovalActions`；**禁止**维护本地步骤话术 Map、对模型输出做截断兜底。

## 关键约定

1. OpenAIChatModel 对接 Gateway `/v1/chat/completions`。
2. Gateway 鉴权注入 `x-user-id`；BFF/Orchestrator 只读，客户端不得自填。
3. Nacos SSOT：改 `docs/nacos/*.yaml` → `sync_nacos.py` → 重启（无 `application-dev.yaml`）。
4. 执行模式：`IntentRouter` → `ExecutionDispatcher`；workflow 图在 **workflow-manager DB**（4.13）。
5. 财务/react 工具经 tool-manager；**禁止** Controller 拼 prompt 模板。
6. `ChatCompletionResponse` 用 `@Builder` 须加 `@NoArgsConstructor` + `@AllArgsConstructor`。
7. 审计：assistant 终态 → RocketMQ / MySQL / ES；`GET /api/audit/recent`。
8. ReAct / workflow agent 节点统一经 `AgentRuntime.run(AgentRunRequest)`。

## 版本与前端

- 勿升 Spring Boot 3.3+；Sa-Token **1.45.0**。**AgentScope 已升 2.0**（native-first，P0–P3 完成；P4–P6 保留自研）。
- ReAct 续跑依赖 **Redis StateStore TTL=7d**；官方自动持久化 + 优雅停机，勿自研 ShutdownHook。
- SSE 基址：生产构建须设 `VITE_BFF_STREAM_BASE`；开发态走 Vite proxy。

### UI 风格

背景统一 **`--sun-black`**、**边框**分区；**禁止**页面/面板/输入用 `--sun-surface` 灰底。代码/Mermaid 主题走 `useTheme` / `mermaidConfig`（`theme: 'base'`）。

## Plan/Spec 文档管理

1. **完成即更新状态**：功能落地后同步更新对应 spec/plan 文档状态为 `✅ 已实现`，禁止代码跑通但文档仍标「实施中」。
2. **完成即归档**：确认完成后移入 `docs/superpowers/specs/archive/`；仅保留正在实施的活跃文档。
3. **Claude.md 进度行同步**：归档时同步更新顶部进度行和 `docs/implementation-plan.md`。
4. **排除项**：各阶段 SSOT 主文档（`phase1`–`phase5`）和 `README.md` 始终保留。

## 其他

- 架构决策（ADR）：[docs/architecture/README.md](./docs/architecture/README.md)。
- 代码加适量中文注释；**禁止**在业务代码中插入多余空行。
- 禁止保存临时脚本；运维统一 **Python**（`scripts/*.py`）。
- 项目中禁止硬编码提示词；正文 SSOT = prompt-manager Catalog（`/prompts`）。
- **禁止 Flyway**；库表 SQL SSOT 在 `docker/mysql/init/`（一项目一文件），禁止放各模块 `resources/db/migration`。
