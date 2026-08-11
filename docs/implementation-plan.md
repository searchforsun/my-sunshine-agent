## 分阶段实施方案

> **前提约束**：兼职投入（1-2天/周）、2-3人全栈小团队、探索型节奏
> **对标方案**：[tech-solution.md](./tech-solution.md)

> **设计文档索引**：[superpowers/specs/README.md](./superpowers/specs/README.md)（阶段一～四 SSOT）  
> **已完成阶段**：任务卡与检查门明细见各 phase SSOT（同上索引）；**本文仅保留未完成检查门、阶段四缺口与前端路由索引**。架构/端口/中间件/版本见 [README.md](../README.md)。

---

### 阶段〇～二（✅ 已完成）

| 阶段 | SSOT | 备注 |
|------|------|------|
| 〇 环境准备 | [README.md](../README.md) §快速开始 | 中间件 + 骨架 |
| 一 底座 | [phase1-foundation-design.md](./superpowers/specs/phase1-foundation-design.md) | 含 1.5 会话 MVP、1.6 Redis 重连 |
| 二 标杆 | [phase2-benchmark-design.md](./superpowers/specs/phase2-benchmark-design.md) | 含 2.9 Workflow、2.10–2.16 收尾 |
| 路由验收 | [routing-golden-set.md](./routing/routing-golden-set.md) | L0–L3 + `executionPreference` |

---

### 阶段三：生产加固（8周）

> **进度（2026-06-30）：** 阶段三 **检查门基本通过**（live 脚本全绿；v6 相对 vector +15% 仍 WARN）。详见 [phase3 SSOT](./superpowers/specs/phase3-production-hardening-design.md) §0/§6。

> 设计 spec（SSOT）：[superpowers/specs/phase3-production-hardening-design.md](./superpowers/specs/phase3-production-hardening-design.md)  
> 索引：[superpowers/specs/README.md](./superpowers/specs/README.md)  
> 实施计划：[superpowers/plans/2026-06-19-phase3-production-hardening.md](./superpowers/plans/2026-06-19-phase3-production-hardening.md)（3.4 等）、[multi-agent-architecture.md](./superpowers/plans/2026-06-19-multi-agent-architecture.md)（3.9–3.12）  
> **主轴**：RAG 双轨评测 + **PLAN_WORKFLOW** + 多租户 / HITL / 全链路可观测

| 任务卡 | 内容 |
|--------|------|
| **3.4** **RAG**（优先） | 3.4.1–3.4.8：**✅ 已实现**（closure 见 `docs/rag/baseline-report.md`、`regression-2026-06-21.md`） |
| 3.2 多租户 | `tenant_id` 字段隔离 + MTM tenant + Sentinel QPS · **✅**（live ✅ 2026-06-27） |
| 3.3 HITL | Catalog `sideEffect` + 确认 UI（含子 Agent）· **✅ live**（`verify_hitl_live --live`） |
| 3.5 可观测 | Grafana RAG + Sentinel Dashboard + 4 告警 · **✅ live** |
| 3.6 审计 | **tool.call ✅** + sub_agent_run ✅ + plan.* ✅ · **live ✅** |
| 3.7 Grounding | 主答复 + 子 Agent output · **✅**（`verify_grounding`） |
| 3.8 提示词 | **✅ 3.8.1–3.8.7** |
| 3.9 PLAN_WORKFLOW | Planner + 动态 DAG + Plan 三 API + DAG/抽屉 UI + **重试/降级/Recovery/HITL** + **用户确认** · **3.9.1–3.9.4 + 3.9.1g ✅**；静态 workflow **物化 Plan 同路径** ✅ · [用户确认 spec](./superpowers/specs/2026-06-27-plan-user-approval-design.md) |
| **3.9.5 阶段三收尾** | **暂停/续跑一致性** · **✅ live** |
| 3.10 AgentRuntime | MAIN/SUB/PLANNER + 工具白名单 · **3.10.1–3.10.6 ✅** |

子 Agent 实现目标（编排器-Worker、`query`+`context` 传入、分层 system、无默认 STM）见 [multi-agent plan §子 Agent 实现目标](./superpowers/plans/2026-06-19-multi-agent-architecture.md#子-agent-实现目标ssot)。
| 3.11 skill-manager | **✅** :8225 + SkillCatalogService + **六种 Skill 触发 live ✅** |
| 3.12 前端 | `/skills` **live ✅**（`verify_skills_ui_live`）；Chat `@` ✅；Plan DAG + 抽屉 ✅；**Plan 用户确认 UI ✅**；**版本 diff ✅**；**Chat 底栏执行模式 P0 ✅** |
| 3.13 并行 | AhoCorasick **✅**；`source_type` **✅**（3.4.2） |
| 3.14 多实例 | Redis GenerationJob 锁 · **✅ 3.14.1** |

#### RAG 量化目标（双轨）

| 轨道 | 指标 | 目标 |
|------|------|------|
| **v5 回归** | Recall@5 / MRR / 正例 EmptyRate | ≥0.98 / ≥0.92 / =0（hybrid+rerank 不退化） |
| **v6 提升** | Recall@5 / MRR vs vector | hybrid+rerank 较 vector **+15% / +10%** |
| **性能** | P95 latency | ≤ **800ms**（hybrid+rerank） |

#### 阶段三检查门（19 条，见 spec §6）

- [x] v5 回归轨 `rag_eval.py` 达标（hybrid+rerank PASS）
- [ ] v6 提升轨：生产门禁 PASS；**相对 vector +15% 轨 WARN**（vector 基线 97.6%）
- [x] Grafana RAG + Sentinel Dashboard + 4 条告警
- [x] 租户 A/B 隔离（3.2）；写工具 HITL live 验收（3.3）
- [x] `PLAN_WORKFLOW` 三 API + Plan 详情/DAG 抽屉（3.12.4 ✅）
- [x] 静态 `WORKFLOW`（L2）Chat 时间线展示 Plan DAG（`StaticPlanAdapter` + `planId=`，见 `routing-golden-set.md` §B–D）
- [x] IntentRouter `plan-workflow` + Planner/校验 **Replan** → 耗尽 **降级 ReAct**（`docs/routing/plan-workflow-retry-degradation.md`）
- [x] 节点 `NodeRetryExecutor` + `on-failure` + Recovery 重试/跳过/终止 + `completed_with_errors` / `degraded_react` 终态
- [x] **3.9.5** Planner 阶段 stop 可续跑；HITL/Recovery 停止后续跑恢复同一交互
- [x] 2+ agent 节点 Plan 演示（`verify_subagent_timeline` ✅）
- [x] skill-manager + `/skills` live；tool/sub_agent/plan 审计可查
- [x] Grounding 集成测试 + 子 Agent 不污染主 reasoning
- [x] `phase2_agent_demo.py --suite all` 仍 PASS

---

### 阶段四：平台化（按需启动）

> 设计 spec（SSOT）：[superpowers/specs/phase4-platformization-design.md](./superpowers/specs/phase4-platformization-design.md)  
> 索引：[superpowers/specs/README.md](./superpowers/specs/README.md)

| 任务卡 | 触发条件 | 说明 |
|--------|----------|------|
| **4.1** RAG 平台化 | 语料运营需求 | **✅ 检查门通过**（2026-07-06；G6 Recall WARN 可选冲刺）· [RAG 索引](./rag/README.md) · [backlog](./rag/backlog.md) · [ADR-002](./architecture/ADR-002-rag-pipeline-in-rag-service.md) |
| **4.2** OCR 入库 L1 | PDF/扫描件/发票 | **✅ 检查门通过**（I1–I9；`.doc`/独立入库 Tab 明确不做）· [backlog](./rag/backlog.md) |
| **4.3** 文档理解 L2 | L1 稳定 | **待做**：版面/表格/quarantine |
| **4.4** 多模态对话 L3 | 聊天发图 | **待做**：Vision + `/chat` 附件 |
| **4.5** Skills 沙箱 | Coding Agent 工作区 | **✅ 方案 B**：常驻 `sandbox__*` + 懒开箱 · 工作区抽屉 · `writeHitlMode` · **单工具取消**（exec/grep/glob）· **Codex 工作区 ✅**（2026-08-03）：绑定 Git repo + 完全体沙箱（bridge 出网 + 精简 ExecGuard）+ 硬件护栏 + 多会话 worktree 任务流 + 「新任务」入口 · [task-workspace-codex spec](./superpowers/specs/archive/2026-07-28-task-workspace-codex-design.md)（§12 演进差异）· [索引](./sandbox/README.md) · Live：`verify_sandbox_live` / `verify_sandbox_workspace_live` / `verify_sandbox_tool_cancel_live` / `verify_agent_workspace_live` · **硬件档位 ✅**（Nacos profiles + 校验 + 前端下拉 + Live 验收） |
| **4.6** 动态 DAG 增强 | Plan 不够用 | **✅**：parallel/exclusive/loop + 校验/布局 · **4.6.4 AutoContextMemory** · Live：`verify_plan_dag_live.py` |
| **4.7** 多 Agent 增强 | 复杂协作 / 交叉验证 / ReAct 软规划 | **✅ 多专家协作完整**（2026-07-08）：**第五模式 `PEER_COLLAB`** L1 §E + **`$` L0** §K · `expert-manager` :8235 + `/experts` · Live：`verify_peer_collab_live` + `verify_expert_consultation_live` · 详设 [expert-consultation](./superpowers/specs/2026-07-07-expert-consultation-design.md)；**4.7.5 ReAct TaskBoard** ✅ · [taskboard](./superpowers/specs/2026-06-24-react-taskboard-design.md)；**4.7.6 Spawn Subagent** ✅（含**单独取消**子任务）· [spawn-subagent](./superpowers/specs/2026-07-18-react-spawn-subagent-design.md) · Live `verify_spawn_subagent_live.py`；**4.7.9 Request Decision** ✅（Chat ReAct MAIN；Cursor 对齐；`decision.enabled` 默认 false / D21；Planner MAIN 延后）· [request-decision](./superpowers/specs/2026-07-28-react-request-decision-design.md) · [cursor-align](./superpowers/specs/archive/2026-08-11-request-decision-cursor-align-design.md) · Live `verify_decision_live.py`；**4.7.1 废弃** / **4.7.4 不做**；**4.7.2** 仍按需 |
| **4.13** Workflow Studio | 业务自助编排 | **✅ 当前形态收口**（MVP 4.13.1–4.13.6 + **4.13.7** 并行/条件分支/循环 + **4.13.8** 变量赋值/参数提取 + **条件复合化**（AND/OR 多条件））；**v1 非目标不做**（for-each、预检测 while、框内嵌套网关/loop、多出边汇合、画布边条件标签）· [workflow-studio spec](./superpowers/specs/2026-06-25-workflow-studio-design.md) · [loop 设计](./superpowers/specs/2026-07-14-workflow-loop-container-design.md) · [结构化 IO spec](./superpowers/specs/2026-07-24-workflow-structured-io-design.md) · [条件复合化 spec](./superpowers/specs/2026-07-28-workflow-composite-condition-design.md) · [实施计划](./superpowers/plans/2026-07-11-workflow-studio.md) |
|| **4.14 Planner-Executor 重建** | 真 Planner-Executor 取代动态 Plan-Workflow | **⬜ 设计评审中（2026-08-05 立项 · v2 简化决议）**：**完全舍弃动态 Plan-Workflow**（一次性 DAG 规划 + PlanApproval + Plan DAG 时间线）→ 重建为 **Planner = ReAct 主 Agent + Worker = 工具调用**（**单一循环**边规划边执行（S5 v4：无 full/hier；细则在 Worker）+ PlanNotebook + **Redis 单写** + **3 类显式触发**重规划；**简化决议 S1-S7**：砍独立 Evaluator→Planner 自判、持久化降级 Redis 单写、去 Tier/压缩点基建、砍 P2 共享内存、取消分解模式枚举、重规划收敛、不复用 PlanValidator）；**静态 Workflow（4.13）保留**、DAG 画布留存给静态 Workflow；新 Timeline 为分层普通时间线 + TaskBoard 一/二级待办（§4 v5）。· [planner-executor-rebuild spec（唯一 SSOT）](./superpowers/specs/2026-08-05-planner-executor-rebuild-design.md) · [archive/harness v8 废案](./superpowers/specs/archive/2026-07-31-planner-harness-loop-design.md) · 实施 H-0→H-7 + 阶段 D（删旧代码） |
| **4.8** 工具集成（SDK + MCP） | 异构系统 / 业务解耦 | **✅ 检查门通过**：MySQL Catalog + `sunshine-tool-sdk` + MCP 动态接入 + `/tools` 管理页 · 详设 [tool-integration spec](./superpowers/specs/2026-07-09-tool-integration-design.md) · 计划 [tool-integration plan](./superpowers/plans/2026-07-09-tool-integration.md) · Live：`verify_tool_integration_live.py --suite all` |
| **4.9** K8s | — | **明确不做**（维持脚本/现有部署） |
| **4.10** Seata | — | **明确不做**（跨服务写靠 HITL + 幂等） |
| **4.11** Prompt 后台 | 非研发维护提示词 | **实施中（backend+UI 近收口）**：详设 [prompt-ops-routing-catalog](superpowers/specs/2026-07-20-prompt-ops-routing-catalog-design.md) · 计划 [prompt-ops plan](superpowers/plans/2026-07-20-prompt-ops-routing-catalog.md)（DB Catalog + 统一 Rule Engine + `/prompts`；首期 draft/published，审核二期）· Live：`verify_prompt_catalog_live.py` |
| **4.12** Serverless | — | **明确不做**（常驻实例） |
| **AS2 升级** | AgentScope 2.0 | **P0–P3 ✅ + P7 ✅（2026-07-26）**：native-first 原子迁移落地——P1 载体+事件层（`ReActAgent`→`HarnessAgent`、Hook→Middleware、`stream`→`streamEvents`）；P2 原生 checkpoint/interrupt + `CompactionConfig` + **指纹缓存 `HarnessAgentHolder`**（E5，非全局单例）+ 官方自动持久化/优雅停机（`disableSessionPersistence()` 为 2.0 no-op 已删）；P3 `enableTaskList`+`TodoTools` 替换自研 TaskBoard（`manage_tasks` 下线）；P7 清死 flag + 全量回归。**P4/P5/P6 经 E5 评审不迁移**——spawn/沙箱/HITL/peer 保留全栈自研（产品语义不可降级，官方无等价物）。闸门：`verify_rollback_p0_compile`/`p1`/`p2_checkpoint`/`p3` + spawn/TaskList/peer/expert/HITL/沙箱/沙箱取消 9 Live 全绿 + orchestrator 732 单测全绿 · 详设 [agentscope-2-upgrade](./superpowers/specs/2026-07-22-agentscope-2-upgrade-design.md) · 计划 [redesign plan](./superpowers/plans/archive/2026-07-23-agentscope-2-native-first-redesign.md)（已归档）；**遗留**：e2e 3 例预存失败（前端 textbox 选择器漂移，与 AS2 无关）、ReAct 停→续跑/kill-15 重启恢复交互式场景留人工验收 |

---

### 阶段五：运营化与开放化（规划）

> 设计 spec（SSOT）：[superpowers/specs/phase5-operation-openness-design.md](./superpowers/specs/phase5-operation-openness-design.md)（2026-07-27 立项）

| 任务卡 | 内容 | 状态 |
|--------|------|:----:|
| **5.1** 对话 Badcase 闭环 | 消息反馈标注 + `/ops` 运营页 + 回流 RAG golden-set + 效果报表 | ⬜ |
| **5.2** 用量计量与配额 | token 落库（`LlmIoTracer` → MQ → MySQL）+ 租户配额 429 + 用量页 | ⬜ |
| **5.3** 多模型场景路由 | `model_route_policy` 表 + `scene` 注入 + `ModelRouter` 扩展（`model=auto`） | ⬜ |
| — | **模型注册表（5.3 前置）** MySQL SSOT + resource-manager CRUD + gateway Adapter/Normalize + orchestrator `ModelSceneResolver` + `/models` UI | **✅**（[archived spec](./superpowers/specs/archive/2026-07-27-model-registry-config-design.md)；Live：`verify_model_registry_live.py`） |
| **5.4** Optimizer MVP | Badcase/评测 → 提案 → prompt/kb draft → 复评对比 → 人工发布（半自动） | ⬜ |
| **5.5** 工具语义检索 | 工具描述 Milvus 索引 + ReAct Top-K 注入 + HITL/元工具白名单 | ⬜ |
| **5.6** 开放 API | `api_key` + Gateway Bearer 校验 + `/open/v1/*`（直转 orchestrator 不经 BFF） | ⬜ |
| **5.7** Prompt 灰度 | canary 版本百分比分流 + 指标对比 + 全量/回滚 | ⬜ |
| 5.8–5.10 | 渠道嵌入 / 组织分级 / ASR·TTS | 按需 |
| — | 通用 A/B 平台、多 Agent 通用消息总线 | **明确不做**（spec §7 D1/D3） |

**前置**：4.11 收口 + AS2 遗留人工验收（spec §7 D5）。**建议顺序**：5.1 → 5.2 → 5.6 → 5.3 → 5.7 → 5.4 → 5.5。

---

### 可观测性增强（6.x · 贯穿阶段三收口 + 阶段五底座）

> 设计 spec（SSOT）：[superpowers/specs/2026-07-27-observability-enhancement-design.md](./superpowers/specs/2026-07-27-observability-enhancement-design.md)（2026-07-27 立项）
> **定位**：补齐 logging(Kibana) / metrics(Grafana) / trace(SkyWalking) 三台端到端闭环 + 前端 LangSmith 式 Run Explorer；**复用** 5.1/5.2/5.3 落库与聚合，不重复建表

| 任务卡 | 内容 | 优先级 | 状态 |
|--------|------|:------:|:----:|
| **6.1** | Logging 集中化 + traceId 关联：logback `%tid` + Filebeat 采集进 ES + 关键日志 JSON 结构化 + Kibana Index Pattern | P1 | ⬜ |
| **6.2** | Metrics 全服务覆盖 + LLM 指标 + 告警落地：全 Java 服务补 prometheus；`LlmMetricsRecorder`（耗时/token/工具调用/降级/熔断）+ orchestrator/tool 指标；Grafana 面板 ×3 + LLM 告警 | **P0** | ⬜ |
| **6.3** | Trace 业务 span 补全 + SSE 串联 + agent 告警：`@Trace` 注解（execution/agent.run/react.loop/workflow.node/tool.invoke/rag.search）；SSE 首事件 traceId；`start.py` agent 缺失显式 WARN | P1 | ⬜ |
| **6.4** | 前端 Run Explorer 观测页（`/observability`）：会话/Run 列表 + 瀑布图（echarts）+ 步骤详情 + 三台外链跳转；BFF 聚合 API（复用 `chat_message.steps` + 5.2 用量表） | **P0** | ⬜ |
| **6.5** | 三台联动：traceId 贯穿前端观测页 / Kibana / SkyWalking / Grafana；`chat_message.trace_id` 落库 | P1 | ⬜ |

**检查门**：`scripts/verify_observability_live.py`（L1 指标/L2 Run 瀑布/L3 Kibana trace_id 命中/L4 SkyWalking 业务 span/L5 Grafana 数据点）。

---

### 前端模块

| 页面 | 路由 | 功能 |
|------|------|------|
| AI 对话 | `/chat` | SSE 流式；workflow **模板**用 `#id`（4.13）非底栏下拉；**静态 Workflow** 展示 `PlanWorkflowPanel` + `PlanNodeDrawer`（DAG 画布）；**Planner-Executor（4.14）** 展示分层普通时间线 + TaskBoard |
| **Plan 详情** | **`/plans/:planId`** | Planner JSON、节点 trace、状态机 |
| 知识库 | `/knowledge` | 知识库工作台（文档/检索调试/参数/评测）；**配置版本化** + suite 管理 · [docs/rag/README.md](./rag/README.md) |
| **Skills** | **`/skills`** | Skill 列表/上传/版本/预览/元数据；**版本 diff** → `/skills/:skillId/diff`（见 [skills-management-ui-design.md](./superpowers/specs/skills-management-ui-design.md)） |
| **Experts** | **`/experts`** | **✅ 阶段四 4.7**：Expert CRUD、Catalog 种子（4 专家）、Chat `$` 补全、`ExpertStepPanel` · [expert-consultation spec](./superpowers/specs/2026-07-07-expert-consultation-design.md) |
| **工具集成** | **`/tools`** | **阶段四 4.8 ✅**：SDK 应用 / MCP Server / 工具集（ReAct + Planner Workflow）/ Plan 执行策略 · [tool-integration spec](./superpowers/specs/2026-07-09-tool-integration-design.md) |
| **工作流** | **`/workflows`** | **阶段四 4.13 ✅ 收口**：Studio 可视化编辑/发布；并行 · exclusive 边条件 · loop；Live `verify_workflow_studio_live` / `verify_exclusive_gateway_live` / `verify_loop_live` |
| **提示词** | **`/prompts`** | **阶段四 4.11 实施中**：Catalog 运营 + 路由 dry-run / ReAct 拼装 · [prompt-ops spec](./superpowers/specs/2026-07-20-prompt-ops-routing-catalog-design.md) · Live `verify_prompt_catalog_live.py` |
| 系统状态 | `/status` | 12 微服务 + 12 中间件状态矩阵 |

> **阶段四 OCR/多模态**：见 `superpowers/specs/phase4-platformization-design.md` §4.2–4.4  
> **阶段四 4.8 工具集成**：见 [tool-integration spec](./superpowers/specs/2026-07-09-tool-integration-design.md) · Live `scripts/verify_tool_integration_live.py`

#### 4.8 工具集成（SDK + MCP）

> 详设：[2026-07-09-tool-integration-design.md](./superpowers/specs/2026-07-09-tool-integration-design.md) · 实施计划：[2026-07-09-tool-integration.md](./superpowers/plans/2026-07-09-tool-integration.md)

| 子任务 | 内容 | 状态 |
|--------|------|:----:|
| **4.8.1** | `common/sunshine-tool-sdk` + finance/oa SDK Demo | **✅** |
| **4.8.2** | MySQL `sunshine_tool` + DB Catalog + 删旧 Handler | **✅** |
| **4.8.3** | SdkDiscoveryPuller + InvokeRouter(sdk) | **✅** |
| **4.8.4** | McpClientPool + probe + import/export | **✅** |
| **4.8.5** | Admin API + 工具集 + Redis catalog-changed | **✅** |
| **4.8.6** | orchestrator ToolSetResolver + kind=mcp | **✅** |
| **4.8.7** | BFF 透传 + sunshine-ui `/tools` | **✅** |
| **4.8.8** | Live 检查门 `verify_tool_integration_live.py` | **✅** |
| **4.8.9** | Catalog Tool ID 规范（`ToolIds`：`sdk__*` / `mcp__*`；LLM function name 同 ID，无转换层） | **✅** |
| **4.8.10** | HITL：`require_confirmation` + `confirmation_edited`（DB 唯一依据；`sideEffect` 只读来自发现） | **✅** |
| **4.8.11** | Plan/Workflow：`execution_mode_policy` 表 + `/tools` 策略编辑；orchestrator `NodeRetryPolicyResolver` 读 DB | **✅** |
| **4.8.12** | 工具集双 Tab（ReAct 默认集 + Planner Workflow 关键工具集）；llm-gateway `LlmIoTracer` 输出 `toolCalls=` | **✅** |

**检查门**：`python3 scripts/verify_tool_integration_live.py --suite all`（G1–G10；MCP 无 npx 时 G4/G5 SKIP）

**调用路径**：静态/Plan Workflow 的 `tool` 节点经 `ToolNodeHandler` 直调 `tool-manager`（不经 LLM `tool_call`）；ReAct 经 LLM `tool_call` → `CatalogRemoteAgentTool` → invoke。Workflow YAML / skill `tools_json` 须使用 Catalog ID（`sdk__*`）。

**技术栈与版本基线、服务器中间件**：见 [README.md](../README.md) §技术栈 · §服务器中间件（ecs4c16g）。
