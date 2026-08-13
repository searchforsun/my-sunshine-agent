# CLAUDE.md

Sunshine AI Platform — 企业级 AI 中台（AgentScope-Java + Spring Cloud Alibaba + Vue3/Naive UI）。

## 编码要义（最高优先级）

1. **两三轮仍不能解决 → 停补丁，查本质**：同一 bug 改 2–3 轮仍反复或出新症状，必须质疑**架构**与 **Catalog 提示词**的合理性；禁止继续打补丁式修改。
2. **找根因，简化设计**：优先从链路建模、SSE/步骤契约、提示词入手修正；方案要**简单**，禁止冗余分支与「兼容旧行为」的兜底逻辑。
3. **模型输出不二次加工**：禁止对模型输出做截断、摘要或过滤兜底；不对就改 Catalog/`/prompts` 或架构。

**进度**：阶段三 ✅ — 阶段四 **4.6 动态 DAG ✅** · **4.7 多智能体协作 ✅** · **4.7.5 ReAct TaskBoard ✅** · **4.7.6 Spawn Subagent ✅** · **4.7.9 Request Decision ✅**（Chat MAIN；Cursor 对齐；Planner 延后）· **异步工具 await ✅**（`background` + `await_tool_run`；exec/spawn；Live `verify_async_tool_await_live.py`）· **4.8 工具集成 ✅** · **4.13 Workflow Studio ✅** · **4.5 沙箱方案 B ✅** · **4.5 Codex 工作区 ✅** · **4.11 Prompt Catalog ✅** · **4.13.8 结构化 I/O ✅** · **4.14 Planner-Executor 重建 🟡**（H-0～H-6 ✅；H-7/阶段 D ⬜；见 [rebuild](docs/superpowers/specs/2026-08-05-planner-executor-rebuild-design.md) §7.0 · [H-6 plan](docs/superpowers/plans/2026-08-13-planner-h6-frontend.md)）· **统一路由 v6 🟡**（R-0～R-3 ✅ / R-4 ⬜；[spec](docs/superpowers/specs/2026-07-29-unified-routing-design.md)·[plan](docs/superpowers/plans/2026-08-13-unified-routing-v6-h5.md)）· **模型注册表 ✅**（MySQL SSOT + `/models` + scene 绑定；5.3 前置；见 [spec](docs/superpowers/specs/archive/2026-07-27-model-registry-config-design.md)）· **服务合并 ✅**（管理类 skill/agent/prompt/desensitize → resource-manager :8240；业务模拟 oa/finance/hr → biz-simulator :8700；tool-manager 更名 tool-service :8210；见 [spec](docs/superpowers/specs/archive/2026-08-03-service-consolidation-design.md)）· 缺口见 `docs/implementation-plan.md`。

## 常用命令

编译、启动、验收命令见 [README.md](./README.md) §快速开始。改 `docs/nacos/*.yaml` 后必跑 `python scripts/sync_nacos.py` 并重启消费服务。修改后端功能后必须重启对应服务的 `start.py`。

**服务启停（`scripts/start.py`）**：服务为**独立进程**（setsid 守护），脚本启动即退出，关闭终端不会带走服务；停服用 `--stop`。

```bash
python scripts/start.py                # 启动全链路（先 SIGKILL 旧进程）
python scripts/start.py --restart      # 打包并重启全链路
python scripts/start.py --restart bff  # 打包并重启指定服务
python scripts/start.py --stop         # 停止全链路
python scripts/start.py --stop bff     # 停止指定服务
```

**运维脚本（SSOT：`scripts/*.py`）**

| 类别 | 核心脚本 |
|------|----------|
| 启停/同步 | `start.py`、`sync_nacos.py`、`sunshine_lib.py` |
| RAG | `rag_reset.py`、`rag_ingest_bulk.py`、`rag_eval.py`、`rag_wipe_and_ingest.py`、`generate_rag_corpus.py`、`verify_chunk_strategies_live.py` |
| 阶段三验收 | `verify_tenant_live.py`、`verify_hitl_live.py`、`verify_skills_ui_live.py`、`verify_pause_resume_consistency.py` |
| 阶段四验收 | `verify_sandbox_live.py`、`verify_sandbox_workspace_live.py`、`verify_sandbox_tool_cancel_live.py`、`verify_spawn_subagent_live.py`、`verify_async_tool_await_live.py`、`verify_decision_live.py`、`verify_external_agent_live.py`、`verify_react_taskboard_live.py`、`verify_tool_integration_live.py`、`verify_workflow_studio_live.py`、`verify_plan_dag_live.py`、`verify_prompt_catalog_live.py`、`verify_enterprise_workflow_live.py`、`verify_loop_live.py`、`verify_exclusive_gateway_live.py`、`verify_personal_rules_live.py`、`verify_model_registry_live.py` |
| 其他 | `clear_session_cache.py`、`download_skywalking_agent.py`、`sync_enterprise_agents.py` |

> **提示词 / 路由规则 SSOT**：`prompt` DB（`/prompts` + Catalog，现聚合于 resource-manager），**不再**经 Nacos `agent.routing.*`。

## 服务端口

| 服务 | 端口 | 说明 |
|------|:---:|------|
| `sunshine-ui` | 5173 | 前端 WebUI（Vue3 + Naive UI） |
| `gateway` | 8000 | Spring Cloud Gateway + Sentinel（统一入口） |
| `bff` | 8001 | WebFlux + SSE 流式转发 |
| `auth-center` | 8100 | Sa-Token 认证中心 |
| `orchestrator` | 8200 | 核心编排（workflow / react / planner-executor / 多智能体协作） |
| `tool-service` | 8210 | 工具注册与调用（SDK + MCP，原 tool-manager） |
| `resource-manager` | 8240 | 聚合管理服务（Skill / Agent / Prompt / Desensitize） |
| `sandbox-service` | 8226 | 沙箱执行环境 |
| `workflow-manager` | 8230 | Workflow 定义 / 版本 / 执行 |
| `llm-gateway` | 8300 | LLM 网关（多厂商路由 / 缓存 / 熔断） |
| `rag-service` | 8400 | RAG 检索（Milvus + Hybrid + Rerank） |
| `biz-simulator` | 8700 | 业务模拟聚合（OA / Finance / HR） |

**中间件**：Nacos `8848/9848` · MySQL `3306` · Redis `6379`（凭据见 [README.md](./README.md) §服务器中间件）。

## 请求链路

Agent 编排要点：`ChatController` → `ExecutionDispatcher` → `StreamToken` → `GenerationJob`（Redis 缓冲 + seq）→ BFF/Gateway 透传 → 前端 `parseSsePayload`。

## 架构与扩展（要点）

**原则**：注册 + Catalog 驱动，禁止 orchestrator/前端硬编码工具 Map。

| 要扩展 | 改哪里 |
|--------|--------|
| 新工具 | 业务 App 引入 `common/sunshine-tool-sdk` 声明 `@SunshineTool` → Nacos 注册 → `/tools` 启用；Workflow 节点 `params.tool` 填 Catalog ID（`sdk__{app}__{name}`） |
| 新 Workflow | `/workflows` + `workflow-manager` DB（唯一 SSOT）+ MySQL init 种子；orchestrator `WorkflowManagerClient` |
| **静态 Workflow** | L2 规则命中 → `WorkflowExecutor` → `StaticPlanAdapter` → `PlanWorkflowPanel`（DAG 画布）；answer prompt 随 workflow 定义维护于 DB `plan_json` |
| **Planner-Executor（4.14）** | `PlannerHarnessExecutor`：Planner=ReAct 主 Agent（**单一循环**边规划边执行，S5 v4：无 full/hier 模式；细则在 Worker）→ Worker=工具调用（`forWorker()`）→ Planner 自判 → 综合回答；`PlanNotebook` + **Redis 单写** + 3 类显式触发重规划；**目标**：完全舍弃动态 Plan-Workflow（`PlanWorkflowExecutor`/`WorkflowPlanner`/PlanApproval，**阶段 D 删除**；当前代码仍存活）；依赖与落地顺序见 [specs/README](docs/superpowers/specs/README.md#活跃增量方案依赖与落地顺序2026-08-13)；详设 [rebuild](docs/superpowers/specs/2026-08-05-planner-executor-rebuild-design.md) |
| **意图路由** | [unified-routing v6](docs/superpowers/specs/2026-07-29-unified-routing-design.md)：**R-0～R-3 ✅**（用户显式 `fast`/`pro`/`workflow` + 双轨收集 + ResourceDispatcher）；**R-4 / 阶段 D ⬜**（删 PlanWorkflow 源码残留）；延期：`intent.classifier` live 版本 bump |
| **Chat 执行模式** | `ExecutionPreference=fast\|pro\|workflow`（旧 `auto`/`react`/`plan-workflow` 读映射）→ `ResourceDispatcher`/`ExecutionDispatcher`；`#` 补全仅工作流模式；冒烟 `verify_routing_v6_smoke.py` |
| **ReAct TaskBoard（4.7.5）** | 元工具 `manage_tasks` + 唯一 `tasks` 步；merge 引擎去重 |
| **ReAct Spawn Subagent（4.7.6）** | 元工具 `spawn_subagent`（仅 MAIN）；上下文隔离；`subagent-*` 卡 + 抽屉；**单独取消**（`SpawnRunRegistry`）；`agentId` 指定预定义智能体，经 `AgentExecutorRouter` 按 `source` 分派 INTERNAL/EXTERNAL（A2A） |
| **ReAct Request Decision（4.7.9）** | 元工具 `request_decision`（仅 MAIN；Nacos `react.decision.enabled` 默认 **false**；**Cursor 对齐**：`title?`+`questions[]` / resolve `answers[]` / `outcome=`）；主时间线 `decision-*`（`phase=decision` / `lifecycle=awaiting`）；`POST .../decisions/{token}/resolve`；暂停/续跑同问卷 re-await；**不做** Planner harness（D12 延后） |
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
| **ReAct spawn_subagent** | 主卡 `subagent-{runId}` + 抽屉 `subSteps`（指定 agentId 时 label 取智能体 displayName）；取消 → `paused` +「已取消」 |
| **ReAct request_decision** | 主卡 `decision-{token}`（`phase=decision`）；`metadata.decision.questions[]`；等待 `lifecycle=awaiting`；resolve `answers[]` → `done`/`outcome=answered`；停止 → `paused`，续跑同问卷 re-await；同消息最多 1 张 awaiting |
| **Workflow（静态）** | 主时间线 `intent → plan → …`（DAG 画布）；agent 节点内部不上主时间线；loop body 进 `subSteps`；answer 节点仅 `step_delta(result)`，勿双写 content |
| **Planner-Executor（4.14）** | 分层普通时间线 `intent → plan(Rn) → worker-* → planner-answer`（非卡片）；TaskBoard 一级=H1、二级=Worker todolist（有则展示）；handoff 仅正文时间线收束并双写 H1 + Planner L1 尾部；**不渲染 Plan DAG** |
| **沙箱工具** | 取消 → `lifecycle=paused`，`summary.after=已取消` |

**reasoning 落点**：ReAct `think*` step、静态 Workflow `node-*` reasoning 挂 node step、Planner-Executor `plan`/`worker` 步。Plan 不合成 think。

**静态 Workflow 节点抽屉**（`PlanNodeDrawer`）：answer/llm → **综合分析** + **最终输出**（原样）；业务节点 **执行记录**（`attempts[]`）；RAG 改写 trace 进抽屉 **检索过程**。

**前端**：`OperationStack` / `PlanExecutionCanvas`（仅静态 Workflow）/ `PlanNodeDrawer` / `TaskBoardPanel`；Planner-Executor 用分层普通时间线 + 一/二级看板（见 rebuild §4）；**禁止**维护本地步骤话术 Map、对模型输出做截断兜底。

## 关键约定

1. OpenAIChatModel 对接 Gateway `/v1/chat/completions`。
2. Gateway 鉴权注入 `x-user-id`；BFF/Orchestrator 只读，客户端不得自填。
3. Nacos SSOT：改 `docs/nacos/*.yaml` → `sync_nacos.py` → 重启（无 `application-dev.yaml`）。
4. 执行模式：`IntentRouter` → `ExecutionDispatcher`；workflow 图在 **workflow-manager DB**（4.13）。
5. 财务/react 工具经 tool-service；**禁止** Controller 拼 prompt 模板。
6. `ChatCompletionResponse` 用 `@Builder` 须加 `@NoArgsConstructor` + `@AllArgsConstructor`。
7. 审计：assistant 终态 → RocketMQ / MySQL / ES；`GET /api/audit/recent`。
8. ReAct / workflow agent 节点统一经 `AgentRuntime.run(AgentRunRequest)`。

## 版本与前端

- 勿升 Spring Boot 3.3+；Sa-Token **1.45.0**。**AgentScope 已升 2.0**（native-first，P0–P3 完成；P4–P6 保留自研）。
- ReAct 续跑依赖 **Redis StateStore TTL=7d**；官方自动持久化 + 优雅停机，勿自研 ShutdownHook。
- SSE 基址：生产构建须设 `VITE_BFF_STREAM_BASE`；开发态走 Vite proxy。

### UI 风格

背景统一 **`--sun-black`**、**边框**分区；**禁止**页面/面板/输入用 `--sun-surface` 灰底。代码/Mermaid 主题走 `useTheme` / `mermaidConfig`（`theme: 'base'`）。

- 所有 UI 区域**禁止**冗余性的解释性说明文字，仅保留必要操作提示，保持简洁。

## Plan/Spec 文档管理

1. **完成即更新状态**：功能落地后同步更新对应 spec/plan 文档状态为 `✅ 已实现`，禁止代码跑通但文档仍标「实施中」。
2. **完成即归档**：确认完成后移入 `docs/superpowers/specs/archive/`；仅保留正在实施的活跃文档。
3. **Claude.md 进度行同步**：归档时同步更新顶部进度行和 `docs/implementation-plan.md`。
4. **排除项**：各阶段 SSOT 主文档（`phase1`–`phase5`）和 `README.md` 始终保留。

## 其他

- 架构决策（ADR）：[docs/architecture/README.md](./docs/architecture/README.md)。
- 代码加适量中文注释；**禁止**在业务代码中插入多余空行。
- 禁止保存临时脚本；运维统一 **Python**（`scripts/*.py`）。
- 项目中禁止硬编码提示词；正文 SSOT = resource-manager Catalog（`/prompts`）。
- **禁止 Flyway**；库表 SQL SSOT 在 `docker/mysql/init/`（一项目一文件），禁止放各模块 `resources/db/migration`。
