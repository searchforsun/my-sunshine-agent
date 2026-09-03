# CLAUDE.md

Sunshine AI Platform — 企业级 AI 中台（AgentScope-Java + Spring Cloud Alibaba + Vue3/Naive UI）。

## 编码要义（最高优先级）

1. **两三轮仍不能解决 → 停补丁，查本质**：同一 bug 改 2–3 轮仍反复或出新症状，必须质疑**架构**与 **Catalog 提示词**的合理性；禁止继续打补丁式修改。
2. **找根因，简化设计**：优先从链路建模、SSE/步骤契约、提示词入手修正；方案要**简单**，禁止冗余分支与「兼容旧行为」的兜底逻辑。
3. **模型输出不二次加工**：禁止对模型输出做截断、摘要或过滤兜底；不对就改 Catalog/`/prompts` 或架构。

**进度**：阶段三 ✅ — 阶段四 **4.6 动态 DAG ✅** · **4.7 多智能体协作 ✅** · **4.7.5 ReAct TaskBoard ✅**（原生 `todo_write`，终态落 MySQL 审计）· **4.7.6 Spawn Subagent ✅** · **4.7.9 Request Decision ✅**（Chat MAIN + Planner D12 ✅；`decision.enabled` 默认关）· **异步工具 await ✅**（`background` + `await_tool_run`）· **4.8 工具集成 ✅** · **4.13 Workflow Studio ✅** · **4.5 沙箱方案 B ✅**（Codex 工作区）· **4.11 Prompt Catalog ✅** · **4.13.8 结构化 I/O ✅** · **4.14 Planner-Executor 重建 ✅**（Planner = 普通 ReAct + 动作元工具 `request_decision`；Worker 独立 sessionId 并行流式 + `await_tool_run`；阶段 D ✅——`PlanWorkflow` 源码与读侧兼容已删；[rebuild spec](docs/superpowers/specs/archive/2026-08-05-planner-executor-rebuild-design.md)）· **统一路由 v6 ✅**（wire 仅 `fast|pro|workflow`，字段 `executionMode`；[spec](docs/superpowers/specs/archive/2026-07-29-unified-routing-design.md)）· **Kind·Biz-Scene Catalog ✅**（资源 `kind` 过滤 · 业务场景 Lab · 工具集 chat/task）· **模型注册表 ✅**（MySQL SSOT）· **服务合并 ✅**（skill/agent/prompt/desensitize → resource-manager :8240 · oa/finance/hr → biz-simulator :8700 · tool-manager → tool-service :8210）· **时间线前缀图标 ✅** · **Usage 状态栏 ✅**（轮次/输入输出/ctx 分组）· **任务清单记忆 ✅（已归档）**——M0 fast 跨轮恢复 / M1 KV Memory + `todo` / M2 pro 终态导出 / M3 session_search（scope `session|workspace`）；[spec](docs/superpowers/specs/archive/2026-08-14-task-list-memory-unification-design.md)）· **L1/L2/L3 上下文压缩 ✅**——L2 kind 精简为 9 类（`process_note` 合并）· L3 语义摘要化 + L2 对账（chat body 层退役）· 压缩点按 kind 分化（chat 4+4 / task 2+2+≤10k）· 工具轮确定性 schema 行 + Near 完整过程装载 · 账本重建校验 `verify_context_rebuild.py`；[spec §5.5/§7.4/§8.2/§13.4](docs/superpowers/specs/2026-07-31-unified-context-compression-design.md)）· **Skill 可发现/触发分离（skill-sticky）✅——活跃方案，当前 v3.22**——技能正文统一 `SkillBodyRenderer`（完整正文 + 声明工具），经 `PromptComposer` 尾部 USER 信封注入（守 C1）；`sunshine_search_skills` 动态加载 + L0 首次绑定统一走「加载技能」步骤；子 agent（spawn/workflow/worker）抽屉同款「加载技能」步骤 + 完整正文；worker 支持 `skillId`；[spec](docs/superpowers/specs/2026-08-12-skill-sticky-process-chain-design.md)）· **账本-视图治理（memory-ledger-view）✅（已归档）**——O1 fast 中断落板 / O2 语义 merge / O3 写路由收敛 `ContextWritePolicy` / O4 账本重建校验；[spec](docs/superpowers/specs/archive/2026-08-24-memory-ledger-view-optimization-design.md)）· **业务上下文权威层（business-context M0–M5）✅（已归档）**——Policy/业务任务/场景偏好三块装载 + M4 冲突仲裁 + M5 embedding 回退/场景双轨 + M0 装配时序；[spec](docs/superpowers/specs/archive/2026-08-13-business-context-authority-design.md)）· **目标对齐与失败预算（react-goal-alignment 4.7.7）✅**——`GoalAlignmentMiddleware` + `FailureBudgetMiddleware`（`react.goal-check`/`tool-failure-budget` 默认关）；失败统一 `[ERROR]` 前缀（AS 2.0 契约）；[spec](docs/superpowers/specs/archive/2026-07-27-react-goal-alignment-design.md)）· 阶段五 **5.2 用量计量 ✅**（token 落库 + 日聚合 `llm_usage_daily` + 租户配额 `tenant_quota`）· **5.3 多模型场景路由 ✅**（`CallSiteKey` SSOT + `model_route_policy` + `model=auto` 按 call_site 路由）· **5.5 工具语义检索 ✅**（Milvus `sunshine_tool_index` + `ToolRetrievalMiddleware` 每轮 Top-K 注入，`tool-inject.mode` full 默认/retrieval）· 缺口见 `docs/implementation-plan.md`。

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
| 阶段四验收 | `verify_sandbox_live.py`、`verify_sandbox_workspace_live.py`、`verify_sandbox_tool_cancel_live.py`、`verify_spawn_subagent_live.py`、`verify_async_tool_await_live.py`、`verify_decision_live.py`、`verify_external_agent_live.py`、`verify_react_taskboard_live.py`、`verify_tool_integration_live.py`、`verify_workflow_studio_live.py`、`verify_plan_dag_live.py`、`verify_prompt_catalog_live.py`、`verify_enterprise_workflow_live.py`、`verify_loop_live.py`、`verify_exclusive_gateway_live.py`、`verify_personal_rules_live.py`、`verify_model_registry_live.py`、`verify_planner_executor_live.py`、`verify_task_list_restore_live.py`、`verify_kv_memory_todo_live.py`、`verify_pro_todo_export_live.py`、`verify_session_search_live.py`、`verify_session_search_workspace_live.py`、`verify_l3_enhancement_live.py`、`verify_taskboard_interrupt_live.py`、`verify_context_rebuild.py`、`verify_goal_alignment_live.py` |
| 阶段五验收 | `verify_model_route_live.py`（5.3 多模型场景路由：auto 池首路由/显式不回归/auto 无策略 400/用量 call_site 落库/策略 CRUD/热更新/语义缓存隔离）· `verify_tool_retrieval_live.py`（5.5 工具语义检索：直调 sync/search 语义命中+minScore 过滤/首轮索引同步/每轮 Top-K 动态注入/指纹幂等/恒注入不分组） |
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
| **Planner-Executor（4.14）** | `PlannerHarnessExecutor`：Planner=ReAct 主 Agent（**单一循环**边规划边执行，S5 v4：无 full/hier 模式；细则在 Worker）→ Worker=工具调用（`forWorker()`）→ Planner 自判 → 综合回答；`PlanNotebook` + **Redis 单写** + 3 类显式触发重规划；**动态 Plan-Workflow 已完全舍弃**（`PlanWorkflowExecutor`/`WorkflowPlanner`/`PlanApproval` 源码与读侧兼容已删，阶段 D ✅）；依赖与落地顺序见 [specs/README](docs/superpowers/specs/README.md#活跃增量方案依赖与落地顺序2026-08-13)；详设 [rebuild](docs/superpowers/specs/archive/2026-08-05-planner-executor-rebuild-design.md) |
| **意图路由** | [unified-routing v6](docs/superpowers/specs/archive/2026-07-29-unified-routing-design.md)：**R-0～R-4 ✅**（用户显式 `fast`/`pro`/`workflow` + 双轨收集 + ResourceDispatcher；读侧兼容已去除，wire 仅 fast/pro/workflow；PlanWorkflow 源码残留已删）；延期：`intent.classifier` live 版本 bump |
| **Chat 执行模式** | wire 字段 `executionMode=fast\|pro\|workflow`；后端枚举统一 `ExecutionMode`（`ExecutionPreference` 已删；存储读侧 DTO 字段仍名 `executionPreference`，值域同三值）→ `ResourceDispatcher`/`ExecutionDispatcher`；`#` 补全仅工作流模式；冒烟 `verify_routing_v6_smoke.py` |
| **TaskBoard（4.7.5 → AgentScope 原生）** | 原生 `todo_write`（AS2 `enableTaskList`）+ 唯一 `tasks` 步；TaskBoard 终态落 MySQL 审计（自研 `manage_tasks` 已下线） |
| **ReAct Spawn Subagent（4.7.6）** | 元工具 `spawn_subagent`（仅 MAIN）；上下文隔离；`subagent-*` 卡 + 抽屉；**单独取消**（`SpawnRunRegistry`）；`agentId` 指定预定义智能体，经 `AgentExecutorRouter` 按 `source` 分派 INTERNAL/EXTERNAL（A2A） |
| **ReAct Request Decision（4.7.9）** | 元工具 `request_decision`（仅 MAIN；Nacos `react.decision.enabled` 默认 **false**；**Cursor 对齐**：`title?`+`questions[]` / resolve `answers[]` / `outcome=`）；主时间线 `decision-*`（`phase=decision` / `lifecycle=awaiting`）；`POST .../decisions/{token}/resolve`；暂停/续跑同问卷 re-await；**Planner D12 ✅**——Planner MAIN 同契约注册 + 续跑（`HarnessPlanner` bind `DecisionResumeSteps`，Planner 时间线决策卡与 Chat MAIN 一致），Worker/SUB 不注入 |
| **沙箱工具取消（4.5.7）** | `sandbox__exec`/`grep`/`glob`：`CancellableToolRunRegistry` + sandbox kill；**同命令重试禁绝**（取消后原样命令拒调，换命令/换参数/换工具放行；v17.13 取代原「同族预算 3」） |

**Agent 运行时**：唯一入口 `AgentRuntime.run(AgentRunRequest)`；SUB 用 `AssembledContext.forSubAgent()`（无 L1/L2/L3）+ `skillId`→`PromptComposer`；禁止绕过 `AgentRunRequest`。

**Tool 链路**：`ToolRegistry` → orchestrator `ToolCatalogService` → `DynamicToolkitFactory`；Catalog ID SSOT：`sdk__*` / `mcp__*`；HITL 读 DB `require_confirmation`。

**Prompt 拼装**：`PromptComposer` 6 层叠加；ReAct 工具策略见 Catalog `mode-overlay.react`（`/prompts`）。

**Query 改写**：仅检索域 → `rag-service` `KnowledgeRetrievalPipeline`（[ADR-002](docs/architecture/ADR-002-rag-pipeline-in-rag-service.md)）；orchestrator 路由域意图改写已退役（`QueryRewriteService`/`rewrite.intent` 已删，改写仅发生在 RAG 检索）。

**RAG 检索策略**：orchestrator `rag.search.strategy` 透传 rag-service（默认 `hybrid+rerank`）。

**可观测性（6.x 设计中）**：三台联动以 `traceId` 贯穿；详设 `docs/superpowers/specs/2026-07-27-observability-enhancement-design.md`。

## 时间线要点

| 模式 | 关键约束 |
|------|----------|
| **ReAct** | `intent → think → tasks? → tool → think-2 → generate`；连续 reasoning 合并为同一 think；SSE 仅下发当前阶段一行 |
| **ReAct spawn_subagent** | 主卡 `subagent-{runId}` + 抽屉 `subSteps`（指定 agentId 时 label 取智能体 displayName）；取消 → `paused` +「已取消」 |
| **ReAct request_decision** | 主卡 `decision-{token}`（`phase=decision`）；`metadata.decision.questions[]`；等待 `lifecycle=awaiting`；resolve `answers[]` → `done`/`outcome=answered`；停止 → `paused`，续跑同问卷 re-await；同消息最多 1 张 awaiting |
| **Workflow（静态）** | 主时间线 `intent → plan → …`（DAG 画布）；agent 节点内部不上主时间线；loop body 进 `subSteps`；answer 节点仅 `step_delta(result)`，勿双写 content |
| **Planner-Executor（4.14）** | 分层普通时间线 `intent → plan → worker-* → planner-answer`；worker 卡 v17.5 起复用子 Agent 抽屉（`WorkerCard` → `PlanNodeDrawer`，任务契约 + contentBlocks + subSteps）；TaskBoard 一级=H1、二级=Worker todolist（有则展示）；**不渲染 Plan DAG** |
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
9. **提示词以线上 DB 为准**：prompt 正文 SSOT = 线上 `prompt_definition`/`prompt_version`（改完 bump `prompt_catalog_meta.catalog_version`，orchestrator 5s 热更新）；种子 SQL（`docker/mysql/init/19-sunshine-resource.sql`）与线上有偏差时**一律以线上为准**回写种子，禁止只改种子或只改线上。

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
- **种子 SQL 必须全量**：`docker/mysql/init/19-sunshine-resource.sql` 是 prompt/skill/agent 等 Catalog 数据的**全量快照**（由线上收敛导出），**不支持增量**；线上有变更（新增/改内容/删除）时必须同步为全量，禁止只补增量 INSERT。
