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
| **4.1** RAG 平台化 | 语料运营需求 | **4.0** pipeline + 多知识库、配置版本化 / 评测 / Suggest · [RAG 索引](./rag/README.md) · **4.1/4.2 检查门留档** [backlog](./rag/backlog.md) · [ADR-002](./architecture/ADR-002-rag-pipeline-in-rag-service.md) |
| **4.2** OCR 入库 L1 | PDF/扫描件/发票 | DashScope OCR → 文本 chunk |
| **4.3** 文档理解 L2 | L1 稳定 | 版面/表格/quarantine |
| **4.4** 多模态对话 L3 | 聊天发图 | Vision + `/chat` 附件 |
| **4.5** Skills 沙箱 | Coding Agent 工作区 | **✅ 方案 B**：常驻 `sandbox__*` + 懒开箱 · 工作区抽屉 · `writeHitlMode` · [索引](./sandbox/README.md) · [详设](./superpowers/specs/2026-07-15-skills-docker-sandbox-design.md) · [方案 B](./superpowers/specs/2026-07-16-conversation-sandbox-permanent-tools-design.md) · Live：`verify_sandbox_live` / `verify_sandbox_workspace_live` |
| **4.6** 动态 DAG 增强 | Plan 不够用 | if-else、并行、Replan、ContextCompressor |
| **4.7** 多 Agent 增强 | 复杂协作 / 交叉验证 / ReAct 软规划 | **✅ 多专家协作完整**（2026-07-08）：**第五模式 `PEER_COLLAB`** L1 §E + **`$` L0** §K · `expert-manager` :8235 + `/experts` · `ExpertCoordinatorService`（选人 + `maxRounds`）· `ExpertHubEngine`（`min-rounds`/`max-rounds`、每轮 continue 判断、第 2 轮起反应式选人）· `ConsultationSynthesizer` · 种子 4 专家（policy/finance/compliance/legal）· Live：`verify_peer_collab_live` + `verify_expert_consultation_live` · 详设 [expert-consultation spec](./superpowers/specs/2026-07-07-expert-consultation-design.md) · 路由基线 [peer-collab spec](./superpowers/specs/2026-06-24-peer-collab-routing-design.md)；**4.7.5 ReAct TaskBoard** ✅ · [taskboard spec](./superpowers/specs/2026-06-24-react-taskboard-design.md)；**4.7.1/4.7.2/4.7.4** 仍按需 |
| **4.13** Workflow Studio | 业务自助编排 | **✅ 当前形态收口**（MVP 4.13.1–4.13.6 + **4.13.7** 并行/条件分支/循环）；**v1 非目标不做**（for-each、预检测 while、框内嵌套网关/loop、多出边汇合、画布边条件标签）· [workflow-studio spec](./superpowers/specs/2026-06-25-workflow-studio-design.md) · [loop 设计](./superpowers/specs/2026-07-14-workflow-loop-container-design.md) · [实施计划](./superpowers/plans/2026-07-11-workflow-studio.md) |
| **4.8** 工具集成（SDK + MCP） | 异构系统 / 业务解耦 | **✅ 检查门通过**：MySQL Catalog + `sunshine-tool-sdk` + MCP 动态接入 + `/tools` 管理页 · 详设 [tool-integration spec](./superpowers/specs/2026-07-09-tool-integration-design.md) · 计划 [tool-integration plan](./superpowers/plans/2026-07-09-tool-integration.md) · Live：`verify_tool_integration_live.py --suite all` |
| **4.9** K8s | — | **明确不做**（维持脚本/现有部署） |
| **4.10** Seata | — | **明确不做**（跨服务写靠 HITL + 幂等） |
| **4.11** Prompt 后台 | 非研发维护提示词 | 版本/审核/回滚 |
| **4.12** Serverless | — | **明确不做**（常驻实例） |

---

### 前端模块

| 页面 | 路由 | 功能 |
|------|------|------|
| AI 对话 | `/chat` | SSE 流式；底栏 **执行路径**选择器（五模式）；workflow **模板**用 `#id`（4.13）非底栏下拉；**静态 / Plan workflow** 均展示 `PlanWorkflowPanel` + `PlanNodeDrawer` |
| **Plan 详情** | **`/plans/:planId`** | Planner JSON、节点 trace、状态机 |
| 知识库 | `/knowledge` | 知识库工作台（文档/检索调试/参数/评测）；**配置版本化** + suite 管理 · [docs/rag/README.md](./rag/README.md) |
| **Skills** | **`/skills`** | Skill 列表/上传/版本/预览/元数据；**版本 diff** → `/skills/:skillId/diff`（见 [skills-management-ui-design.md](./superpowers/specs/skills-management-ui-design.md)） |
| **Experts** | **`/experts`** | **✅ 阶段四 4.7**：Expert CRUD、Catalog 种子（4 专家）、Chat `$` 补全、`ExpertStepPanel` · [expert-consultation spec](./superpowers/specs/2026-07-07-expert-consultation-design.md) |
| **工具集成** | **`/tools`** | **阶段四 4.8 ✅**：SDK 应用 / MCP Server / 工具集（ReAct + Planner Workflow）/ Plan 执行策略 · [tool-integration spec](./superpowers/specs/2026-07-09-tool-integration-design.md) |
| **工作流** | **`/workflows`** | **阶段四 4.13 ✅ 收口**：Studio 可视化编辑/发布；并行 · exclusive 边条件 · loop；Live `verify_workflow_studio_live` / `verify_exclusive_gateway_live` / `verify_loop_live` |
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
