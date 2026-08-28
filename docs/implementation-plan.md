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
| 路由验收 | [routing-golden-set.md](./routing/routing-golden-set.md) | L0–L3 + `executionMode`（v6：`fast`/`pro`/`workflow`）；冒烟 `verify_routing_v6_smoke.py` |

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
| 3.9 PLAN_WORKFLOW | Planner + 动态 DAG + Plan 三 API + DAG/抽屉 UI + **重试/降级/Recovery/HITL** + **用户确认** · **3.9.1–3.9.4 + 3.9.1g ✅**；静态 workflow **物化 Plan 同路径** ✅ · [用户确认 spec](./superpowers/specs/archive/2026-06-27-plan-user-approval-design.md) |
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
| **4.7** 多 Agent 增强 | 复杂协作 / 交叉验证 / ReAct 软规划 | **✅ 多专家协作完整**（2026-07-08）：**第五模式 `PEER_COLLAB`** L1 §E + **`$` L0** §K · `expert-manager` :8235 + `/experts` · Live：`verify_peer_collab_live` + `verify_expert_consultation_live` · 详设 [expert-consultation](./superpowers/specs/archive/2026-07-07-expert-consultation-design.md)；**4.7.5 ReAct TaskBoard** ✅ · [taskboard](./superpowers/specs/archive/2026-06-24-react-taskboard-design.md)；**4.7.6 Spawn Subagent** ✅（含**单独取消**子任务）· [spawn-subagent](./superpowers/specs/archive/2026-07-18-react-spawn-subagent-design.md) · Live `verify_spawn_subagent_live.py`；**4.7.9 Request Decision** ✅（Chat ReAct MAIN；Cursor 对齐；`decision.enabled` 默认 false / D21；**Planner D12 ✅**——Planner MAIN 注册 `request_decision` + 暂停/续跑同契约）· [request-decision](./superpowers/specs/archive/2026-07-28-react-request-decision-design.md) · [cursor-align](./superpowers/specs/archive/2026-08-11-request-decision-cursor-align-design.md) · [D12](./superpowers/specs/archive/2026-08-12-react-request-decision-planner-d12.md) · Live `verify_decision_live.py`；**异步工具 await** ✅ · [async-tool](./superpowers/specs/archive/2026-08-12-async-tool-await-design.md) · Live `verify_async_tool_await_live.py`；**4.7.7 目标对齐与失败预算** ✅（2026-08-26）——`AgentRunState` + `GoalAlignmentMiddleware`（MAIN-only 每 N 轮目标对齐）+ `FailureBudgetMiddleware`（ERROR 计数/成功清零/双维度阈值）+ `SandboxAgentTools` `[ERROR]` 前缀契约收口 · [goal-alignment](./superpowers/specs/2026-07-27-react-goal-alignment-design.md) · Live `verify_goal_alignment_live.py`（G1–G4）；**4.7.1 废弃** / **4.7.4 不做**；**4.7.2** 仍按需 |
| **4.13** Workflow Studio | 业务自助编排 | **✅ 当前形态收口**（MVP 4.13.1–4.13.6 + **4.13.7** 并行/条件分支/循环 + **4.13.8** 变量赋值/参数提取 + **条件复合化**（AND/OR 多条件））；**v1 非目标不做**（for-each、预检测 while、框内嵌套网关/loop、多出边汇合、画布边条件标签）· [workflow-studio spec](./superpowers/specs/archive/2026-06-25-workflow-studio-design.md) · [loop 设计](./superpowers/specs/archive/2026-07-14-workflow-loop-container-design.md) · [结构化 IO spec](./superpowers/specs/archive/2026-07-24-workflow-structured-io-design.md) · [条件复合化 spec](./superpowers/specs/archive/2026-07-28-workflow-composite-condition-design.md) · [实施计划](./superpowers/plans/2026-07-11-workflow-studio.md) |
|| **4.14 Planner-Executor 重建** | 真 Planner-Executor 取代动态 Plan-Workflow | **✅ H-0～H-7 代码 ✅ / Live P1–P7 全绿 ✅ / 阶段 D ✅**（PlanWorkflow 源码与读侧兼容已删）：[rebuild §7.0](./superpowers/specs/2026-08-05-planner-executor-rebuild-design.md) · [H-7 live](./superpowers/plans/2026-08-13-planner-h7-live.md) ✅ · routing R-4 ✅ · `verify_planner_executor_live.py --suite all` 通过 · **v17.7 Worker 并发流式 + 重试机制 ✅**（dispatch 强制 background + TaskItem 版本化 t1-1/t1-2/t1-3 + failReason 分类 + 重试上限 2 次）· **v17.8 并发/取消/工具 9 项修复 ✅**（并发上限 10 · worker await 参数 · Planner 注册 await_tool_run · task_status 工具 · 取消 dispose 终止流式）· **v17.9 await_tool_run 批量等待 ✅**（runIds 数组一次收集同轮多任务 · AsyncToolRunRegistry.awaitMany · planner.harness v4）· **v17.10 并发流式根因修复 + 记号统一 ✅**（Worker 独立 sessionId 解除 AgentScope callGates 串行 · formatTaskUnitId 统一记号 T5-2/T1-1）· **v17.11 版本记号首版归一 + 取消历史保留 + 全局取消乐观态 ✅**（TaskItem.versionedId 首版 t1-1 · mergeTaskQueue 保留 fail/cancelled 历史 · 重派自动版本化 t1-2 · stop 乐观标 worker 已取消 · planner.harness v6）· **v17.12 Spawn 子 Agent 并发流式 + 续跑保持已取消 ✅**（SUB 独立 sessionId 并行流式 · isResumableReactStep 移除 subagent 续跑保持已取消）· **v17.13 异步等待契约统一 ✅**（AwaitToolRunTool 资格判定改 runId 作用域——MAIN/WORKER/PLANNER 可 await 自己派发的 run · 新增 async_status peek 工具全角色注册 · DynamicToolkitFactory.buildForWorker · mode-overlay.react v5 + harness.worker v10 统一 AsyncTool 段禁臆造工具名）· **v17.14 Spawn 并发卡片即时显示 + 卡片运行态标题 ✅**（resolveFlushMessageId main run 反查——非 HITL main bridge auxiliary 直刷 GenerationJob，根治主 Agent 工具调用阻塞期间 hookQueue 不 drain、并发 spawn 子 Agent 卡刷新才显示 · 前端 resolveRunningChildStepLabel——SubagentCard/WorkerCard running 时任务名后跟当前子步标题深度思考/执行命令）· **v17.15 PRO 路径工具审计上下文绑定 + 卡片运行态标题修正 ✅**（PlannerHarnessExecutor 入口 bindToolAudit——PRO 路径此前缺审计绑定，worker/planner spawn_subagent 全被「缺少会话审计上下文」拒绝、子 Agent 不启动前端无卡 · resolveRunningChildStepLabel 跳过 tasks/intent/skill 脚手架步只取动态子步不再误显「任务清单」 · SubagentCard 移除冗余「正在执行：{label}」行 · child-label 样式对齐 summary 去「·」）· **v17.16 卡片运行态显示阶段正文内容 ✅**（resolveRunningChildStepBody 取代 label 版——think/rag 显示 reasoning 思考正文、tool 显示 detail 工具输出，单行化 + 90 字截断；generate 最后正文阶段固定显示「正在收尾回复」；正文空回退阶段标题；仍跳过脚手架步）· **v17.17 Worker 内 spawn 子 Agent 卡嵌套进 worker 抽屉 ✅**（SpawnSubagentTool emit 目标按 activeBridge 判定——Worker 内 spawn 折叠进 worker-{taskId}.subSteps 抽屉内嵌套子 agent 卡，主 Agent 直接 spawn 仍直发 mainBridge · flushCancelTokens 取消终态同判定）· **v17.18 运行态正文折叠 + taskQueue 独立下发 + flush 虚拟线程 ✅**（WorkerTimelineBridge/SpawnSubagentTimelineBridge.wrap 移除 isReasoning 过滤——think 子步 reasoning 增量折叠进 subSteps，卡片实时显示阶段正文/「正在收尾回复」· GenerationFlushScheduler.flushPartial/flushStepsPartial 转投 VirtualThreadExecutors——scrub 的 Mono.block 不再落 Netty 事件循环线程，根治「SSE cancelled」正文流式中断 · StepMetadata 新增独立 taskQueue 字段 + withTaskQueue、PlannerActionTool 改发 taskQueue、ProcessingStepSerde 落库；前端 isHarnessBoard 基于 taskQueue、TaskBoardPanel 任务名前加 T1-1 记号且样式与任务内容一致）· **v17.20 Worker 卡按任务序号稳定排序 ✅**（sortSteps 对 worker-* 卡优先按任务序号 T1/T2/T3 稳定排序——并发派发 began startedAt 毫秒级随机、auxiliary 直刷到达顺序不定按时间戳排序跳位；同序号重派按版本升序 t1-1<t1-2、描述后缀 t1-arch 视为无版本排最后；与 think/tool 混排仍按时间戳不插队）|
| **4.8** 工具集成（SDK + MCP） | 异构系统 / 业务解耦 | **✅ 检查门通过**：MySQL Catalog + `sunshine-tool-sdk` + MCP 动态接入 + `/tools` 管理页 · 详设 [tool-integration spec](./superpowers/specs/archive/2026-07-09-tool-integration-design.md) · 计划 [tool-integration plan](./superpowers/plans/2026-07-09-tool-integration.md) · Live：`verify_tool_integration_live.py --suite all` |
| **—** | **任务清单记忆（M0/M1/M2/M3）** | **✅ M0**（2026-08-23）：fast 跨轮任务板恢复——`task_board` 最近快照未完成项注入【任务板】块（仅 FAST 新消息，L3 前渲染，零新存储）；单测 `TaskBoardRestoreServiceTest`/`ContextMessageBuilderTest` · Live `verify_task_list_restore_live.py`（T1–T4）· **✅ M1**（2026-08-23）：KV Memory 统一 + `kind=todo`——`user_context_state` 加 `scope` 列 + `context.memory.extract` 参数化（v22 门禁 + 完成即 void + 乱序保护）+ `L2StateStore` scope 读写路由 + `AssembleRequest` kind/workspaceId 闸门 · Live `verify_kv_memory_todo_live.py`（T1–T5 全绿）· **✅ M2**（2026-08-24）：pro 终态导出——`H1TodoExportService` + `PlannerHarnessExecutor.doFinally` 三态收束导出 + `L2StateStore.syncTodoExport` 全量对比 void + key `task.{goalHash8}.{baseTaskId}`（task→workspace / chat→user scope 分流）· Live `verify_pro_todo_export_live.py`（P1–P6 全绿）· **✅ M3**（2026-08-24）：session_search 收缩版——`SessionSearchTool`（task 会话 MAIN 注册 `sunshine_session_search`，scope=session 仅本会话正文）+ rag-service convId 过滤（`ChatHistoryMilvusService.buildSearchExpr`）+ `ContextAssembler` task 跳过 L3 自动注入 + Nacos `react.session-search.enabled`；单测 `SessionSearchToolTest`/`ChatHistorySearchExprTest`/工厂注册断言 · Live `verify_session_search_live.py`（P1–P4 全绿）· **✅ M3 扩展 workspace**（2026-08-26）：`SessionSearchTool` scope 扩为 `session\|workspace`——workspace 时反查 workspaceId → `ChatConversationRepository.findTaskIdsByWorkspace` 展开 task 会话并排除当前 → `workspace-max-convs` 截断（默认 20）→ rag-service `conv_id IN [...]`（`ChatHistoryMilvusService`/`ChatHistoryRetrievalService`/`ChatHistoryController`/`HistoryRagClient` 四层 convIds 透传）+ Nacos `react.session-search.workspace-max-convs`；单测 convIds IN 表达式 + workspace 五例 · Live `verify_session_search_workspace_live.py` W1–W4（同工作区 A/B 两会话：A 落 body 标记 → B `scope=workspace` → 日志 `工作区跨会话检索 convs=1` + 模型复述标记）· [task-list-memory spec](./superpowers/specs/2026-08-14-task-list-memory-unification-design.md) |
| **—** | **L3 增强 v26** | **✅ 已实现**（2026-08-24）：语义提取层（`LLMSemanticExtractor` + Catalog `context.l3.semantic-extract`，abstain 默认，与 L2 解耦）+ turn-pair 攒批触发（N=3 轮 / M=5 分钟）+ 相似度去重（cosine 0.95 跳过 / 0.85-0.95 合并，**按 layer 隔离**防 semantic/process 精炼段被 body 跨层误删）+ Milvus schema 扩展（`layer`/`scene`/`status` 字段重建 collection）+ task process 层向量化（`ProcessingStep.result` 截断 200 chars）+ 分层 TTL 定期维护（chat 30d / task-body 90d / task-process 7d / task-semantic 90d）+ `deleteByFilter(scene,layer,status)` 状态过滤；**v26.2 body 层非全量**（2026-08-26）——语义提取按轮判定（prompt v2 二维数组）作 body 置信门禁，abstain 轮 body+semantic 均不落库（`agent.context.l3.body-gate-enabled` 默认 true）；单测 orchestrator+rag 全绿 · Live `verify_l3_enhancement_live.py`（V-L3-1~6 全绿）· [spec §7.4/§13.4](./superpowers/specs/2026-07-31-unified-context-compression-design.md) |
| **—** | **L3 摘要化与 L2 对账 v28** | **✅ 已实现**（2026-08-27）：**chat 场景 L3 只保留 semantic 摘要层**（body 原文层退役——`L3IngestService.ingestTurnPair/ingest/flush` chat 恒不写 body，消除「用户/回答一个 chunk 一个 chunk」零散结构；task 保留 body+process 供 `session_search` 深挖）；**语义提取摘要化（方案1）**——Catalog `context.l3.semantic-extract` v4（合并同主题连续对话为一段、保留 ID/数字/时间关键细节、每轮 ≤2 段）+ 排除 L2 已结构化覆盖内容；**L2 写入对账（方案2）**——`LLMSemanticExtractor` 写 semantic 前读该用户 active L2 `stateValue`，整段包含某条 L2 值 → abstain（强命中，查询失败保守不拦截）；**chat 召回/展示收敛**——`L3RecallService` layers body+semantic→仅 semantic，`listL3Entries` 面板仅 semantic 且 role 统一「Chunk」，Milvus `listByConv` 透出 layer，`ContextAdminService.reingest` chat 提示不写 body；单测 orchestrator 1431 + rag 全绿、vue-tsc 通过 · [spec §7.4/§13.4.1](./superpowers/specs/2026-07-31-unified-context-compression-design.md) · [task-scene（仅标注 task 不受影响）](./superpowers/specs/2026-08-01-task-scene-context-design.md) |
| **—** | **压缩点延后项收口（§5.5 ①③⑥⑮）** | **✅ 已实现**（2026-08-26）：① 同步推进 P——assemble 超预算 → `L1Compressor.advanceCompressionPoint` 零 LLM 纯写库前移压缩点 + 本轮按新 P 重组；**P/S 分离**（`far_folded_msg_ids`=退役边界 / 新列 `far_summarized_msg_ids`=已折叠子集，间隙轮写路径异步补折叠）· ③ Budget 退役并入——压缩点模式 `applyBudgetAtPoint` 丢 L3→退役 Mid 进 P→丢 Far 块，Near/L2 永不丢（滑动窗保持静默丢弃基线）· ⑮ ≤10k 硬预算——`task-post-compact-budget` + `enforcePostCompactBudget` · ⑥ Tier 定序——`PromptComposer` scope-prompt 静态前置 / nodePrompt 尾部；**②⑤⑦ 追加收口**（② L3 尾部定序 / ⑤ 跨轮压缩单入口——架构核实已满足；⑦ 幂等增益判定——`L2StateStore.refreshSameValue` 同值零增益跳过写库，`L2StateStoreFilterTest` 幂等专项 + 全量 1291/1291 全绿，Live 两轮同陈述既有行 `updated_at`/`source_msg_id` 零重写）；O4 `verifyRebuild` 补 `gapRounds` + S7 间隙漂移；Live `verify_context_rebuild.py` 扫描/self-test 全绿 · [spec §5.5/§8.2/§13.3](./superpowers/specs/2026-07-31-unified-context-compression-design.md) · [task-scene §4.2.1](./superpowers/specs/2026-08-01-task-scene-context-design.md) |
| **—** | **chat 压缩点二期（§5.5.4 ④ / §5.5.7 差异表）** | **✅ 已实现**（2026-08-26）：`L1Compressor.compressionPointActive` 启用面扩至 `chat×fast|pro`（workflow 仍退出）；`CompressionPoint` 加 `chat-near-keep-rounds`/`chat-mid-keep-rounds`（默认 4/4），`compressByCompressionPoint`/`advanceCompressionPoint` 按 kind 选参数（task 2/2 + ≤10k / chat 4/4 无硬预算，chat 靠组装侧 Budget 退役并入收敛）；chat 差异项天然满足（L3 scene=chat 保留、KV user scope、无任务清单恢复块/P0）；单测 1345/1345 全绿（新增 chat 4+4 压缩 / chat 超预算不折叠 Near / task 超预算对照折叠 / advance chat 4 轮保底 / assemble chat×fast 分区 5 例）；Live 验收 chat 9 轮短对话 → `compression-point kind=chat` 日志、Near 不裁剪、折叠 2 轮进 far_summary（P=S gap=0）、L1 行 near_n=4/mid_n=4、rebuild-check 三会话全 PASS · [spec §5.5.4④/§5.5.7/§13.3 ④](./superpowers/specs/2026-07-31-unified-context-compression-design.md) · [task-scene §2.2](./superpowers/specs/2026-08-01-task-scene-context-design.md) |
| **—** | **工具轮确定性 schema 行（⑫⑬⑭⑲）** | **✅ 已实现**（2026-08-26）：`StepMetadata` 加 `toolArgs`（`ToolArgsRenderer` 白名单标量入参渲染——金额/单据/员工号等，禁整段 payload）+ `toolExitCode`（`SandboxExitCodeHolder` 透传 exec 退出码），`ProcessingStepMiddleware` 工具步开步暂存/收口落库；`ToolSchemaRenderer` 从 `chat_message.steps` JSON 确定性渲染 `[toolName] keyArgs=… status=ok|fail|denied exit=? · result≤200 · refs=[path]`（零 LLM；写/改类省略 result 禁 patch 原文；RAG 步映射 `search_knowledge`）；四处历史构建点（`ChatStreamContextFactory` 新建/续跑 · `ContextWritePath` · `ContextAdminService` rebuild/print）统一经 `SessionTurn.fromMessage` 附 `toolSchemaLines`；Near/Mid 渲染原样附加（§5.5.8 chat Mid 结论压缩 + 同形 schema；§6.5 task Mid 短结论机械截取 `extractShortConclusion` 前 2 句 ≤120 字，零 LLM）；单测 `ToolSchemaRendererTest` 10 例 + `ContextAssemblerTest` Near/Mid 附行断言 + `L1CompressorTest` 短结论 3 例 · orchestrator 全量 1313/1313 全绿（新增 `TaskProcessRendererTest` 3 例 + `renderProcessLine` 2 例 + `toChatTurns` task 完整过程渲染 2 例）· **task Near 完整过程装载（§6.6）✅ 已实现（2026-08-26）**：`TaskProcessRenderer` 从 steps 渲染 think 推理全文（`think: …`，`reasoning` 未收口跳过）+ tool 序列原文（写/改 `sandbox__write/edit` 保留 patch 原文，读/执行 ≤200+refs，同 §6.5 分级）；`SessionTurn` 加 `processLines`（task 场景 `fromMessage` 填充，四处历史构建点传 kind）；`ContextAssembler.toChatTurns` task assistant 轮渲染完整过程，`l1OverBudget`/`applyBudgetAtPoint`/`trimByTokens` 预算估算按完整渲染内容计（超限走压缩点退役，不静默丢 Near）；Live `task×fast` 端到端：think reasoning + rag 步落库、R2 Near=2 装载、rebuild-check PASS · [spec §5.5.8/§13.3](./superpowers/specs/2026-07-31-unified-context-compression-design.md) · [task-scene §6.5](./superpowers/specs/2026-08-01-task-scene-context-design.md) |
| **—** | **业务上下文权威层（business-context M1–M5）** | **✅ 已实现**（2026-08-26）：读侧装载——`BusinessContextAssembler` 闸门（Nacos `agent.business-context.enabled` ∧ kind=chat ∧ scene 非空）→ 三块按 policy > task > prefs 序：**Policy**（resource-manager 新端点 `/api/biz-scenes/policies/active` 全租户 active 快照 → `BizSceneCatalogClient` 启动预热 + 5min 刷新；消费侧解析租户精确 > `*` 平台默认 ∧ 生效窗 ∧ 最高 version）· **business_task 召回阶梯**（新表 `business_task` @ sunshine_chat：同场景活跃时间窗 → 会话锚定 → 最近 1 条详情 + ≤top-k 极简目录；done/archived 不进 Prompt）· **场景偏好**（`user_context_state` 扩 `biz_scene_scope`/`confirm_status`；preference ∧ confirmed ∧ 未过期 ∧ key ∈ 白名单）；`ReactExecutor` 资源召回后注入 `injectedBlocks`（落点 = L1 上下文层之后、当前 user 消息之前）；**M4 冲突仲裁 ✅**（2026-08-26）：`BizContextConflictArbiter`——闸门（`conflict-check.enabled` ∧ scene ∧ L3 非空）→ 权威参照（`renderPolicyBlock`/`renderTaskBlock` 同源）无则放行 → LLM 判定（Catalog `context.biz-scene.conflict-check`，`=== USER ===` 分 system/user）→ `{"filter":[{"snippet":…}]}` 段落过滤 → 失败兜底 drop/keep + `BIZ_CONTEXT_CONFLICT` 审计；`ReactExecutor` scene 提局部变量 + `AssembledContext.withL3MaterialBlock` 替换 memory；默认关（企业工具/工单类对话手动打开）；**M5 embedding 回退 + 场景双轨 ✅**（2026-08-26）：`biz_scene_definition` 加 `description_vector`/`source`/`source_conversation_id`/`approved_by`/`approved_at` + 状态扩展 `pending_review`/`rejected`/`auto_cleaned` + 索引（线上 `migrate_biz_scene_dual_track.sql`）；resource-manager `embedding-index`/`{code}/vector` 端点 + auto 创建即 `pending_review` + 审核升 active 记审核人；orchestrator `SceneEmbeddingService`（DashScope `text-embedding-v4` + 余弦 + 索引缓存 + 懒回填，**事件循环线程安全**——embed HTTP 统一 boundedElastic + `Future.get`，修读路径 reactor-http 上 `block()` NonBlocking 异常）· 读路径 `ReactExecutor.resolveBizScene` 未命中 → embedding 回退 · 写路径 `SceneWriteResolver` ① 路由种子 → ② embedding → ③ `SceneAutoCreateService` LLM 自动创建（Catalog `context.biz-scene.auto-create` + 既有场景表注入；防污染：≥2 轮 / max-pending 20 / rate-limit 3 每 10 分钟 / 相似度 ≥0.85 抑制重复）→ `biz_scene_scope` 注入 L2 抽取；`pending_review` 仅嵌入检索不装载 Policy/任务板；前端 BizScenesView 双 Tab + 审核；Nacos `agent.business-context.scene-embedding.*`/`scene-auto.*` 默认关；单测 `SceneEmbeddingServiceTest`/`SceneAutoCreateServiceTest`/`SceneWriteResolverTest` 10 例（含事件循环线程回归）+ orchestrator 全量 1379/1379 全绿 · Live `verify_scene_dual_track_live.py` A（端点）/B（懒回填+无召回 null）/C（auto 创建+落库）/B′（读/写路径 embedding 命中 pending_review）/D（审核落库）/E（清理还原）全绿 · **M0 装配时序拆分 ✅**（2026-08-26）：`AssembleRequest.deferL3`（fast×chat 生效）——`ContextAssembler.assemble` 路由前仅底座（日志 `l3=0`）+ `AssembledContext.L3Anchor` 分区锚点（Near/Mid 排除 ID + Far ID + farSummary 标记）；`ReactExecutor` 资源召回后 `ContextAssembler.attachL3` 装配 L3（排除 Near/Mid 已覆盖 + 剩余预算裁剪 + 先于 M4 仲裁）；pro/workflow 保持 `deferL3=false` 现状；单测 7 例（defer 挂锚点 / attachL3 注入 / 无锚点跳过 / 已含 L3 防重 / 超预算丢弃 / recall 失败降级）+ orchestrator 全量 1386/1386 全绿 · Live fast×chat 验证路由前零 L3 召回 + attachL3 调用（rag 检索请求） + Near 覆盖排除正确 · [spec](./superpowers/specs/2026-08-13-business-context-authority-design.md) |
| **—** | **Skill 可发现/触发分离（skill-sticky）** | **✅ 已实现（主干）**（2026-08-24）：**S-0** chat_message 落 `routing_skill_ids`/`routing_agent_ids` + 续跑/新建复用 `RoutingSeed`（`ConversationService.updateMessageExecutionPlan`/`loadRoutingSeed` + `ChatStreamContextFactory`）；**S-D** `context.skill-directory` 名+描述目录、召回不灌 overlay（`SkillCatalogService.renderDiscoverableForPrompt`）；**S-T** L0 短路（`ForcedExecutionRouter`）+ triggered skillIds 全链路 + skill 工具 schema 召回；**S-1** 轻 sticky——`RoutingStickyService` L0 整表替换 / 无触发继承，agentIds 跨轮接续（`ExecutionPlanRouter.applySeed`）；**A-1/A-6** spawn 双轨——预定义 agent 子工具 = (tenant,kind) ∩ 声明、动态 sub `tool_ids` ⊆ 集 + 缺省同款集（`ToolSetResolver.intersectToolSet` + `SpawnSubagentTool`）；**A-7** spawn-hint 工具清单渲染 + v3.13 抽屉 skill 加载步骤（`AgentCatalogService.renderForSpawnHint`）；单测 orchestrator 1189 全绿 · Live `verify_skill_sticky_live.py` · ⏳ **A-2~A-4**（skill 租户 / agent 写侧 / picker 过滤）· ⏳ **S-C**（双阈值采纳 / 候选动态加载）· ⏳ **v3.6 retrieval 双层**（工具规模超阈值）· [spec](./superpowers/specs/2026-08-12-skill-sticky-process-chain-design.md) |
| **4.9** K8s | — | **明确不做**（维持脚本/现有部署） |
| **4.10** Seata | — | **明确不做**（跨服务写靠 HITL + 幂等） |
| **4.11** Prompt 后台 | 非研发维护提示词 | **✅ 已实现（backend+UI 收口）**：详设 [prompt-ops-routing-catalog](superpowers/specs/archive/2026-07-20-prompt-ops-routing-catalog-design.md) · 计划 [prompt-ops plan](superpowers/plans/2026-07-20-prompt-ops-routing-catalog.md)（DB Catalog + 统一 Rule Engine + `/prompts`；首期 draft/published，审核二期）· Live：`verify_prompt_catalog_live.py` |
| **4.12** Serverless | — | **明确不做**（常驻实例） |
| **AS2 升级** | AgentScope 2.0 | **P0–P3 ✅ + P7 ✅（2026-07-26）**：native-first 原子迁移落地——P1 载体+事件层（`ReActAgent`→`HarnessAgent`、Hook→Middleware、`stream`→`streamEvents`）；P2 原生 checkpoint/interrupt + `CompactionConfig` + **指纹缓存 `HarnessAgentHolder`**（E5，非全局单例）+ 官方自动持久化/优雅停机（`disableSessionPersistence()` 为 2.0 no-op 已删）；P3 `enableTaskList`+`TodoTools` 替换自研 TaskBoard（`manage_tasks` 下线）；P7 清死 flag + 全量回归。**P4/P5/P6 经 E5 评审不迁移**——spawn/沙箱/HITL/peer 保留全栈自研（产品语义不可降级，官方无等价物）。闸门：`verify_rollback_p0_compile`/`p1`/`p2_checkpoint`/`p3` + spawn/TaskList/peer/expert/HITL/沙箱/沙箱取消 9 Live 全绿 + orchestrator 732 单测全绿 · 详设 [agentscope-2-upgrade](./superpowers/specs/archive/2026-07-22-agentscope-2-upgrade-design.md) · 计划 [redesign plan](./superpowers/plans/archive/2026-07-23-agentscope-2-native-first-redesign.md)（已归档）；**遗留**：e2e 3 例预存失败（前端 textbox 选择器漂移，与 AS2 无关）、ReAct 停→续跑/kill-15 重启恢复交互式场景留人工验收 |

---

### 阶段五：运营化与开放化（规划）

> 设计 spec（SSOT）：[superpowers/specs/phase5-operation-openness-design.md](./superpowers/specs/phase5-operation-openness-design.md)（2026-07-27 立项）

| 任务卡 | 内容 | 状态 |
|--------|------|:----:|
| **5.1** 对话 Badcase 闭环 | 消息反馈标注 + `/ops` 运营页 + 回流 RAG golden-set + 效果报表 | ⬜ |
| **5.2** 用量计量与配额 | token 落库（llm-gateway `TokenUsageCollector` → MQ `llm-usage` → orchestrator 消费落库 `llm_usage_record` + `/api/usage/*` 查询）· 租户配额 429 · 用量页 | **阶段一 ✅（2026-08-27）**——落库闭环达成（Live 验证一次对话全链路各次调用落库且 estimated=false）；**阶段二 ✅（2026-08-27）**——日聚合 `llm_usage_daily`（`UsageDailyAggregationJob` + est_cost 模型单价估算 + `/api/usage/daily`）· 配额 `tenant_quota`（orchestrator CRUD + `/check` + llm-gateway 请求前校验 429 明确错误码，`llm.usage.quota.enabled` 默认关）· `/ops` 用量页（`OpsView` 用量/配额双 Tab）；Live：白名单外/月度超限 429 + 开关关恢复放行；**后置**：5.3 `call_site` 链路透传后聚合/配额按调用点细化 |
| **5.3** 多模型场景路由 | `model_route_policy` 表 + `scene` 注入 + `ModelRouter` 扩展（`model=auto`） | **✅（2026-08-27）**——`CallSiteKey` 枚举 SSOT + `model_route_policy`（resource-manager，`20-sunshine-model-registry.sql` 种子）+ call_site 全链路透传（AgentScope transport 按角色 / `LlmGatewayClient` 默认 summarize / IntentRouter rewrite）+ `ModelRouter.resolveEffectiveModel`（auto 按池选首个 enabled，生效模型回写；无策略 400）+ Redis 热更新 + 语义缓存隔离（auto 不入缓存 + key 含 call_site）+ `/models` 路由策略 Tab；单测 llm-gateway 45/45 全绿 · Live `verify_model_route_live.py` R1–R7 全绿；**后置**：5.3.5 Grafana 面板 call_site×model |
| — | **模型注册表（5.3 前置）** MySQL SSOT + resource-manager CRUD + gateway Adapter/Normalize + orchestrator `ModelSceneResolver` + `/models` UI | **✅**（[archived spec](./superpowers/specs/archive/2026-07-27-model-registry-config-design.md)；Live：`verify_model_registry_live.py`） |
| **5.4** Optimizer MVP | Badcase/评测 → 提案 → prompt/kb draft → 复评对比 → 人工发布（半自动） | ⬜ |
| **5.5** 工具语义检索 | 工具描述 Milvus 索引 + ReAct Top-K 注入 + HITL/元工具白名单 | **✅（2026-08-27）**——rag-service `ToolMilvusService`（collection `sunshine_tool_index` 租户隔离 + flush 落盘保证立即可见）+ `ToolIndexService`（embedding 复用 `EmbeddingService`）+ `/api/tool-index/sync|search`（Nacos `rag.tool-index.*`）；orchestrator `ToolRetrievalService`（恒注入判定内置/沙箱/HITL + 指纹幂等同步 + Tier 0 目录渲染）+ `ToolRetrievalMiddleware`（每轮检索 Top-K 激活组）+ `DynamicToolkitFactory` 分组注册 + `ReActSystemPromptResolver` Tier 0 名列表 + `ReActAgentRuntime` 首轮预置激活组；Nacos `tool-inject.mode`（full 默认/retrieval，失败回退全量）；单测 orchestrator 1424 + rag-service 167 全绿 · Live `verify_tool_retrieval_live.py` T1–T4 全绿；**后置**：5.5.5 golden-set 工具选择评测 |
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
| **Skills** | **`/skills`** | Skill 列表/上传/版本/预览/元数据；**版本 diff** → `/skills/:skillId/diff`（见 [skills-management-ui-design.md](./superpowers/specs/archive/skills-management-ui-design.md)） |
| **Experts** | **`/experts`** | **✅ 阶段四 4.7**：Expert CRUD、Catalog 种子（4 专家）、Chat `$` 补全、`ExpertStepPanel` · [expert-consultation spec](./superpowers/specs/archive/2026-07-07-expert-consultation-design.md) |
| **工具集成** | **`/tools`** | **阶段四 4.8 ✅**：SDK 应用 / MCP Server / 工具集（ReAct + Planner Workflow）/ Plan 执行策略 · [tool-integration spec](./superpowers/specs/archive/2026-07-09-tool-integration-design.md) |
| **工作流** | **`/workflows`** | **阶段四 4.13 ✅ 收口**：Studio 可视化编辑/发布；并行 · exclusive 边条件 · loop；Live `verify_workflow_studio_live` / `verify_exclusive_gateway_live` / `verify_loop_live` |
| **提示词** | **`/prompts`** | **阶段四 4.11 ✅ 已实现**：Catalog 运营 + 路由 dry-run / ReAct 拼装 · [prompt-ops spec](./superpowers/specs/archive/2026-07-20-prompt-ops-routing-catalog-design.md) · Live `verify_prompt_catalog_live.py` |
| 系统状态 | `/status` | 12 微服务 + 12 中间件状态矩阵 |

> **阶段四 OCR/多模态**：见 `superpowers/specs/phase4-platformization-design.md` §4.2–4.4  
> **阶段四 4.8 工具集成**：见 [tool-integration spec](./superpowers/specs/archive/2026-07-09-tool-integration-design.md) · Live `scripts/verify_tool_integration_live.py`

#### 4.8 工具集成（SDK + MCP）

> 详设：[2026-07-09-tool-integration-design.md](./superpowers/specs/archive/2026-07-09-tool-integration-design.md) · 实施计划：[2026-07-09-tool-integration.md](./superpowers/plans/2026-07-09-tool-integration.md)

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
