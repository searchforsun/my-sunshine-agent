# Planner-Executor 架构重建 — 取代动态 Plan-Workflow

> **状态**：✅ **已完成（2026-09-03 归档）**——H-0～H-8 全部落地 · H-7 Live **P1–P9 全绿**（`verify_planner_executor_live.py --suite all`）· 阶段 D 源码删除 ✅ · **v17.7 Worker 并发流式 + 重试机制 ✅**（dispatch 强制 background + await_tool_run 收集；TaskItem 版本化 t1-1/t1-2/t1-3 + failReason 分类 + 重试上限 2 次；见 §3.3.1）· **v17.18 运行态正文折叠 + taskQueue 独立下发 + flush 虚拟线程 ✅**（Worker/子 Agent 的 reasoning 折叠进 subSteps → 卡片实时显示阶段正文/「正在收尾回复」；TaskBoard 独立 `taskQueue` 下发 + T1-1 记号；`GenerationFlushScheduler` 转投虚拟线程根治 Netty 线程 block 导致的 SSE 中断；见下方 v17.18 变更记录）· **v17.19 抽屉右上角层级栈式退出 ✅**（worker 抽屉下钻子 agent 卡入栈，右上角逐层「返回上级」，最顶层才关闭；见下方 v17.19 变更记录）· **v17.20 Worker 卡按任务序号稳定排序 ✅**（`sortSteps` 对 `worker-*` 卡按任务序号 T1/T2/T3 稳定排序——并发派发 began startedAt 毫秒级随机、auxiliary 直刷到达顺序不定，按时间戳排序卡片跳位；同序号重派按版本升序；见下方 v17.20 变更记录）
> **v2（2026-08-05）**：简化决议 S1–S7（§0.1）。**v3（2026-08-07）**：§3.1.1 上下文契约。**v4（2026-08-10）**：S5 单一循环（无 full/hier）。**v5（2026-08-10）**：§4 分层普通时间线 + TaskBoard。**v6（2026-08-10）**：归档 harness；§5.0 PlanNotebook 定稿模型 + §5.4 S6 重规划表迁入本文。
> **v7（2026-08-13）**：勘误 D6/`PlanValidator`/Redis key/TTL/GoalAlignment/`CollapsibleConfirmPanel`；**长负载预算**上调（§5.0 / §8.1，对齐现网 `react.task-max-iters` / spawn·exec-wall）。
> **v8（2026-08-13）**：命名对齐 routing **四轴**——会话形态 `kind`（废 `scene`）；执行模式 `executionMode`；业务域 `biz_scene`（暂不动）；LLM 调用点 `callSite` / DB·MQ `call_site`（废 `call_scene`）。正文若仍写 `scene=chat|task` 均读作 `kind`。
> **v9（2026-08-13）**：§7 落地进度——H-0～H-4 + 过渡入口 ✅。
> **v10（2026-08-13）**：H-5 ✅（routing v6：`fast`/`pro`/`workflow` + ResourceDispatcher；`pro`→harness）。
> **v11（2026-08-13）**：H-6 ✅（分层时间线 + Composer UX；TaskBoard H1 待 harness `tasks` SSE）；H-7 全量 Live / 阶段 D（R-4）**未做**。
> **v12（2026-08-13）**：对照代码冻结 H-7 缺口；实施计划 [planner-h7-live](../../plans/archive/2026-08-13-planner-h7-live.md)。
> **v13（2026-08-13）**：H-7 **代码**落地（`tasks` SSE / handoff / `planner-answer` / follow-up obsolete / `plan.worker_*` / `verify_planner_executor_live.py`）；单测绿；**全量 Live 需部署 orchestrator 后跑脚本**；阶段 D 仍后置。
> **v14（2026-08-14）**：阶段 D 核对完成——`WorkflowPlanner`/`PlanWorkflowExecutor`/`PlanApproval*`/`PLAN_WORKFLOW` 路由入口源码**零残留**；`PlanMaterializer`/`PlanNormalizer`/`PlanTimeline`/`PendingInteraction`/`ResumeInteractionHint` 经代码核对为**静态 Workflow / HITL / Recovery 复用**，从舍弃表改列保留（§2.1）；routing spec R-4 同步 ✅。
> **v15（2026-08-17）**：**动作工具化**——`plan_submit`/`self_assess` 两个 AgentTool 取代文本 JSON 输出协议（根治「模型手写非法 JSON」整类故障）；`HarnessPlanner` 按 `DispatchSession.ActionSignals` 判定动作收到与否；`planner.harness` 只写决策逻辑，字段契约由工具 schema 承载（§3.2 / §8.2）。
> **v16（2026-08-17）**：**时间线平铺**——harness 时间线取消工具组折叠与多轮折叠、worker 行及内部时间线不缩进；Planner 元工具（`plan_submit`/`self_assess`/`dispatch_worker`）补中文名（`ToolCatalogService` 内建表）与专属图标（§4.1）。
> **v17（2026-08-18）**：**架构重构——Planner 一次性 ReAct run**。v15/v16 的「时间线平铺」实操中暴露反复打补丁反模式（评估进展重复、自判与任务脱节、TaskBoard in_progress 状态缺失等），根因在 v15 把 Planner 的规划 / 派发 / 自判拆为 Loop 引擎驱动的 3 次独立 ReAct run。本版根除之：
>   1. **`HarnessPlanner.runPlanned()` 取代 `planNext + selfAssess + synthesizeAnswer`**：Planner 一次性 ReAct run，think + tool_call 连续流；`plan_submit / dispatch_worker / self_assess` 三个 AgentTool 取代文本阶段协议；Planner **停止调工具**直接输出 content = 综合回答。
>   2. **`PlannerHarnessLoop` 收敛为熔断 + 启动**：删除 wave / `executeTaskWithRetries` / `resolveAssessDecision` / `hasNoOutstandingWork` / `GoalAlignmentValidator` 调用 / 兜底 Worker 调度；只保留 `planner.runPlanned + 墙钟熔断 + store.save`。
>   3. **`WorkerDispatchTool` 立即 emit taskBoard 快照**：`replaceTaskStatus(in_progress)` 后立刻 `emitTaskBoardSnapshot("worker-start")`——TaskBoard 在 Worker 执行期间显示 → 箭头（in_progress）。
>   4. **`AgentExecutionProperties.Harness.Planner` 新增 `maxIters=30`**：Planner 一次性 ReAct 的轮数上限（Nacos SSOT 已加 `agent.execution.harness.planner.max-iters`）。
>   5. **取消"评估进展 / 提交调度计划 / 综合回答 Planner 内部 think"等独立步骤**——它们是 v15 的引擎补丁；本版 Planner 是普通 ReAct，与 Subagent 同构。
> **v17.1（2026-08-18）**：**Worker/spawn 父步快照节流**——`WorkerTimelineBridge` / `SpawnSubagentTimelineBridge` 对 Worker 内部 `step_delta` 内容增量（think reasoning / tool output 等）按 **200ms 合并**下发父步快照（`step` 结构变化与 `complete` 终态快照不受节流），根治「每 token 下发一次含完整 subSteps 的父步快照」导致的 **SSE 事件风暴**（实测单 Worker 上千个冗余 step 事件、2.12MB 流量，前端时间线重渲染被拖垮、worker 卡流式内容无法展示）。节流后单 Worker 事件量降至 ~150，流式粒度 200ms，最终一致性由结构 step + 终态快照兜底。§3.2 ② 的同构声明同步适用。
> **v17.2（2026-08-18）**：**前端两处修复**（think 复用丢内容 + harness 折叠判定加固）——
>   1. **think 复用不清空 reasoning**：`upsertStep` 原先对「复用 think 的 running 快照」将 `reasoning` 置 `undefined`（注释意图为 `longerText` 保留 prev、后续 `step_delta` 续写累加，代码与注释矛盾）。Planner 连续推理复用同一 think 卡（`beginReasoningRound` REUSE → resume running）时，前一段思考内容被清空，后端 resume 后又只下发新内容增量（不回放旧段）→ 前半段思考永久丢失。改为统一 `longerText`（§3.2 ④ 同构声明适用）。实测 Q2 场景 think-1 reasoning 78→362 字符，两段自动合并。
>   2. **pro 主对话 harness 判定加固**：`isHarnessTimelineMessage` 原依赖 worker 步存在才判定 harness；Worker 步未折叠进主时间线（foldActive=false / Worker 独立平铺）时，pro 主对话会退化为普通 ReAct 触发 roundGroup 折叠（「调用N次工具」吞掉 planner think/工具行）。新增 **Planner 元工具步信号**（`plan_submit` / `self_assess` / `dispatch_worker` 任一 tool 步即视为 harness 分层平铺），保证 pro 主对话永不折叠；fast / 静态 Workflow DAG 判定不受影响。
> **v17.3（2026-08-19）**：**Worker 正文流式 + 去除「任务结果汇总」子步**——根因：`WorkerDispatchTool.foldStepToken` 只折叠 `isStep()/isStepDelta()`，把 content 三类 token 整个丢弃，导致 Worker 正文根本没进 `WorkerTimelineBridge.wrap` 的 content 路由（管线已就绪但入口漏判，与 `SpawnSubagentTool.foldStepToken` 同构声明脱节）；`WorkerTimelineBridge.complete` 再把 handoff 文本作为「任务结果汇总」`phase=handoff` 子步一次性塞进 subSteps，正文被收束而非流式。修复：①`foldStepToken` 补 `isContent/isContentStart/isContentEnd` 三类（与 spawn 对齐）；②`complete` 删 handoff 子步注入，result 改由 `parentStepUpdate` 承载（终稿兜底）；③清理死代码（`WorkerDispatchTool.wrapForEmit`、前端 `buildHarnessTimelineEntries`/`resolveWorkerHandoffText`/`.op-harness-handoff` 样式）。§4.1 示例图 / §4.3 步骤契约同步修订。
> **日期**：2026-08-05（v17：2026-08-18；v17.3 / v17.4 / v17.4.1 / v17.5：2026-08-19；v17.6 / v17.7 / v17.8：2026-08-20；v17.9 / v17.10 / v17.11 / v17.12 / v17.13 / v17.14 / v17.15 / v17.16 / v17.17 / v17.18 / v17.19 / v17.20：2026-08-21）
> **v17.4（2026-08-19）**：**Worker 展开区正文去重**——v17.3 后 Worker 正文同时存在于父步 `contentBlocks`（分段 content 经 `SubAgentContentTokens.route` scope 到 `worker-*`，前端写入 `step.contentBlocks`）与父步 `result`（`complete` 终稿兜底），前端两处渲染导致**双份正文**：嵌套 `OperationStack` 的 `contentRowsAfterStep` 渲染 contentBlocks 一份；`OperationCard` 的 `resolveStepExpandInner` 回退 `step.result` 作 `expandBody` 又渲染一份。修复（与 spawn subagent 同构——spawn 卡不渲染 result，正文在抽屉）：`resolveStepExpandInner` / `resolveStepExpandLead` 对 `phase=worker` / `id.startsWith('worker-')` 步返回空，`OperationCard` op-detail 对 worker 步跳过，正文唯一由嵌套 `OperationStack` 的 contentBlocks 承载。`result` 保留在数据层作终稿兜底/历史回放（contentBlocks 终态落库 `chat_message.steps` JSON），不再渲染。单测 `processingStepsDisplay.worker.test.ts` 覆盖。
> **v17.4.1（2026-08-19）**：**Worker 折叠透露正文修复 + 任务摘要布局优化**--v17.4 后 worker done 步 `summary.after=null`，`resolveStepSummaryFull` 回退 `step.result` 作折叠主行预览，导致折叠态透露正文。修复：`resolveStepSummaryFull` 对 worker 步返回空（折叠只显示任务名，正文由嵌套 stack 承载）。另：`harness.worker` 提示词 v6 任务摘要由三列横排（过程|结论|未决）改为两列竖排（字段|内容），避免窄容器三列长内容挤在一起；单测补折叠预览断言。
> **v17.5（2026-08-19）**：**Worker 迁移到子 Agent 抽屉显示架构**——并发多 Worker 时前端仅能展示一个流式输出的根因是 Worker 正文在主时间线 inline 穿插、折叠展开互斥；改为与 spawn subagent 完全同构的**抽屉架构**：①后端 `WorkerTimelineBridge` 父步 metadata 携带**任务契约**（`contextFactory.buildStablePrefix(task)` 经 `metadata.spawnPrompt` 下发）；②前端新增 `WorkerCard`（复用 SubagentCard 结构，worker 播放环图标，点击打开 `PlanNodeDrawer`）；③`PlanNodeDrawer` 支持 `worker` 节点（标题「任务契约」展示注入提示词 + contentBlocks 穿插 + subSteps 执行时间线，全部复用 agent 分支）；④`hitlSteps.findAgentNodeStep` 支持 `worker-*` 前缀，抽屉 live 跟随；⑤Worker 正文不再主时间线 inline 穿插（正文收进抽屉），折叠态零泄漏。并发 Worker 各自卡片独立点击打开抽屉，互不干扰。
> **v17.6（2026-08-20）**：**Worker prompt 去除工具白名单枚举**——白名单由 `AgentRunRequest.toolWhitelist` 在运行时控制 Toolkit 注册（模型只能看到已注册工具），prompt 内再枚举一遍属冗余 token 且可能误导。修复：`WorkerContextFactory.buildStablePrefix` 删除 `## 工具白名单` 追加段与 `formatWhitelistNote`，`build` 签名精简为 `build(TaskItem)`；同步删死代码（`WorkerTimelineBridge.taskContract()` getter、`build` 的 `nb`/`harness` 未用参数）。§3.1.1 上下文契约 / §7 决策表同步修订。
> **v17.7（2026-08-20）**：**Worker 并发流式 + 重试机制**——`dispatch_worker` 从 `blockLast()` 阻塞改为 `Flux.subscribe()` fire-and-forget（复用 spawn_subagent `background=true` 机制），解决多 worker 并发时 token 被缓冲无法流式输出的问题；同时实现完整状态机：失败/取消不原地重试，保留历史记录（t1-1, t1-2, t1-3），最多重试 2 次（执行 3 次），failReason 分类（超时/异常/用户取消）。§3.2 Worker 状态机 / §7 决策表同步修订。
> **v17.8（2026-08-20）**：**并发/取消/工具 9 项修复**——①同消息并发派发上限 3→**10**（`react.async-tool.max-concurrent-per-message`）；②Worker await 独立参数 `worker-await-*`（默认 120s / 上限 600s / **6 次**等待，适配 pro 复杂长任务，`AsyncToolRunRegistry` kind 分派）；③**Planner toolkit 补注册 `await_tool_run`**（此前仅 `think_summary`，模型幻觉 `wait_async_results`/`task_list`/`task_output` 空转的根因）+ `ReActAgentRuntime` 将 PLANNER 一并注册 main run（满足 `activeMainBridge` 判定「仅主 Agent 可调用」）；④新增 **`task_status` 元工具**——查询全量任务状态元数据（taskId/label/status/retryIndex/failReason/dependsOn），决策重派/收束前先查，`ToolCatalogService` 中文名「查询任务状态」+ `ProcessingStepMiddleware` 主行中文名/展开 JSON；⑤前端 `WorkerCard`/`TaskBoardPanel` 拼接执行单元记号（t1-1，后端记号展示）；⑥**Worker 取消真正终止流式**——`WorkerDispatchTool` 捕获 `Disposable` 订阅，用户取消/整体取消先 `dispose()` 再写终态，fold guard 阻止取消后继续折叠正文；⑦整体取消（输入框 stop）经 `cancelByMessage` 级联至全部 Worker run。§3.2 动作协议 / §8.1 Nacos / §9.1 单测同步修订。
> **v17.9（2026-08-21）**：**`await_tool_run` 批量等待**——修复提示词「对每个 runId 依次等待」的低效描述与文档「支持多 runId 批量等待」声明未落地的漂移：`AwaitToolRunTool` 新增 `runIds` 数组参数（`runId` 单值兼容），`AsyncToolRunRegistry.awaitMany` 并行等待同轮多 run（共享观察窗口，返回 `runs[]`，未终态含 running 快照，已终态不消耗 waitCount）；`planner.harness` 提示词升级 v4——同轮任务用 `runIds` 一次批量收集，禁止逐任务串行等。
> **v17.10（2026-08-21）**：**并发流式根因修复 + 执行单元记号统一**——①**根因**：同消息多个 Worker 复用同一 `HarnessAgent` 实例（fingerprint 缓存）且 RuntimeContext 沿用 `assistantMessageId` 作 `sessionId`，落入 AgentScope **per-instance `callGates`** 的 `(userId, sessionId)` 串行槽——同一时刻仅一个 Worker 的 LLM 流式输出（前端呈现「一个一个输出」）。修复：`ReActAgentRuntime` 对 `AgentRole.WORKER` 改用 `worker-{runId}` 独立 sessionId，各 Worker 槽独立并行流式（§8.2 同构声明适用）。②前端 `formatTaskUnitId` 统一执行单元记号：`r5-quality-2 → T5-2`（序号取 taskId 首个数字、版本取末尾 `-N`）、`t1-1 → T1-1`，WorkerCard 与 TaskBoard 共用；记号样式去边框、等宽加粗适配文字。
> **v17.11（2026-08-21）**：**版本记号首版归一 + 取消历史保留 + 全局取消乐观态**——①**首版就带版本**：`TaskItem.versionedId()` 无 `-N` 后缀时补 `-{retryIndex}`（t1 → t1-1），`HarnessTaskBoardProjector` 与 `WorkerTimelineBridge` 统一用它——首次执行显示 **T1-1**、重派 T1-2，WorkerCard/TaskBoard 一一对应，重派新卡（worker-t1-2）独立不覆盖历史卡；②**mergeTaskQueue 保留失败/取消历史**：同名新 `plan_submit`（pending）不覆盖 fail/cancelled 旧条目，Planner 续跑重派经 `dispatch_worker` 传 base taskId 命中取消记录自动版本化 t1-2/t1-3（根治「续跑 plan_submit 覆盖 taskQueue，重派退化为同名 t1 原地执行、TaskBoard 无法区分尝试次数」）；③**前端 stop 乐观更新 worker 卡取消态**：`pauseRunningWorkflowNodes` 覆盖 `worker-*` 步，全局取消（BFF SSE 已断、后端 paused 终态送达不了）时立即本地标「已取消」；④`planner.harness` 升级 **v6**（线上 v5 草稿缺 `await_tool_run timeout_sec?` 参数说明，发布 v6 补齐对齐种子）——重派语义强化（勿 plan_submit 覆盖同名取消历史，直接 dispatch_worker 传 base id）+ await `timeout_sec` 引导（worker 默认观察窗口 **120s**，复杂长任务可传 300~600，上限 600；前端耗时显示即实际观察窗口）。
> **v17.12（2026-08-21）**：**Spawn 子 Agent 并发流式 + 续跑保持已取消**——①`ReActAgentRuntime` 对 `AgentRole.SUB` 同样改用 `subagent-{runId}` 独立 sessionId（此前仅 WORKER 独立），同消息多个 `spawn_subagent` 不再落入共享 `assistantMessageId` 串行槽，真正并行流式（与 Worker 同构，§8.2 声明适用）；sessionId 仅作 AgentScope callGates 串行 key，不影响 checkpoint/持久化。②前端 `isResumableReactStep` **移除 subagent**：spawn runId 每次新建，续跑不重放旧 `subagent-*` 卡——全局取消后标「已取消」的卡续跑保持终态，不再被重置为 pending「等待中」（此前与 worker 不一致，worker 从未在重置名单）。
> **v17.13（2026-08-21）**：**异步等待契约统一**——根治「Worker 等待子 Agent / sandbox__exec 无法触发等待、回查任务状态工具 worker 专有、其余场景幻觉工具名」的三叉问题：①`AwaitToolRunTool` 资格判定从「仅主 Agent / 拒 sub」改为 **runId 作用域**——`runId` 为派发方上下文才可见的 UUID 随机句柄，MAIN / WORKER / PLANNER 均可 await 自己派发的 exec / spawn / worker run，Worker 内部 await 自己的 sandbox__exec / spawn_subagent 不再被误拒（§3.3.1 工具注册表修订）；②新增通用 **`async_status(runIds[]/runId)`** peek 工具——立即回查异步 run 状态（done/running/…）**不等待、不消耗 await 预算**，与 `await_tool_run` 分工（先查再等），MAIN / WORKER / PLANNER 全角色注册，替代 Worker 专有 `task_status` 的跨角色误用；③`DynamicToolkitFactory` 新增 **`buildForWorker`**（SUB 基础：RAG/沙箱/think_summary + `await_tool_run` + `async_status` + `spawn_subagent`，Worker 为完整 fast ReAct 可隔离子工作），`ReActAgentFactory.resolveToolkit` 路由 `AgentRole.WORKER`（§8.2 工具集矩阵修订）；④`ProcessingStepMiddleware` 对 `async_status` 主行中文名 + 展开 JSON，`ToolCatalogService` 内建显示名补 `async_status`/`await_tool_run`；⑤提示词对齐——`mode-overlay.react` v4/v5 统一 AsyncTool 段（`background` + `await_tool_run(runIds[]/runId)` 批量收集 + `async_status` 状态回查 + exec/spawn/worker 三档观察窗口 30s/120s/120s + 禁臆造 wait_async_results/task_list/task_output 工具名），`harness.worker` v10 新增「异步与隔离子工作」段（背景同构）；⑥**沙箱取消预算改为同命令重试禁绝**——用户取消 `exec`/`grep`/`glob` 后按 messageId 记录 `(toolName, 命令/pattern 签名)`，**同命令原样重试拒调，换命令/换参数/换工具放行**（取代原「同族预算 3」——取消一条命令即禁绝整个同族的副作用会让 Worker「换方案继续」失去沙箱能力，与提示词语义矛盾）；`CancellableToolRunRegistry` 移除 `followupBudget`/`cancelMaxFollowups` 计数，`sandbox.cancel-result`/`sandbox.budget-exhausted` 升级 v2 文案。§3.2 工具注册 / §9.1 单测同步修订。
> **v17.14（2026-08-21）**：**Spawn 并发卡片即时显示 + 卡片运行态标题**——①**根因**：`spawn_subagent` 子 Agent 的 begin 卡 / 折叠快照经 `StepEventBridge.emit(mainBridge,…)` 走 `routeHookToken`，非 HITL 的 main bridge `resolveFlushMessageId` 解析不到 assistantMessageId（fallback 返回 bridgeId，`generationFlush` 以 assistantMessageId 为 key 查不到）→ `canFlush=false` → 卡入 hookQueue。主 Agent 在 spawn 工具 `dispatch().blockLast()` 阻塞等子 Agent 完成期间主 Flux 无事件 → hookQueue 不 drain → begin 卡堆积到 spawn 返回后才 emit（**前端只有刷新浏览器才看到子 Agent 卡**）。修复：`resolveFlushMessageId` 增加 `mainRunByMessage` 反查——bridgeId 命中 main run 即返回其 assistantMessageId，main bridge 的 auxiliary（spawn begin 卡、worker 折叠快照）`canFlush=true` **直刷 GenerationJob**（与 HITL/Worker 已验证路径一致），并发 spawn 时卡片即时出现、内容实时流式。②**卡片运行态标题**：前端新增 `resolveRunningChildStepLabel`——SubagentCard / WorkerCard running 时从 `subSteps` 取首个 running 子步（think→「深度思考」、sandbox exec→「执行命令」、工具→「调用工具 {displayName}」）展示在任务名后（· 分隔、muted 小字，与正文 shimmer 同步），无 running 子步时不显示。§8.2 / §9.1 同步修订。
> **v17.15（2026-08-21）**：**PRO 路径工具审计上下文绑定 + 卡片运行态标题修正**——①**根因**：`bindToolAudit` 仅在 `ReactExecutor`（FAST）/`AgentNodeHandler`（WORKFLOW）绑定，**PRO（Planner-Executor）路径从未绑定** `toolAuditContext(assistantMsgId)`——Worker/Planner 内 `spawn_subagent`（以及 `request_decision`）走到审计校验即被拒，子 Agent 根本没启动、前端无卡片（实测日志：Worker 自述「5 个隔离调研子任务因平台缺少会话审计上下文而未能派发」）。修复：`PlannerHarnessExecutor.execute` 入口与 ReactExecutor 一致绑定 `StepEventBridge.bindToolAudit`（conversationId/assistantMsgId/userId/tenantId/persistedPlanId/kbId/conversationKind），worker/planner 的 spawn 得以通过审计 → `activeMainBridge(proMsgId)` 命中 planner main run → begin 卡直刷主时间线，子 Agent 真正并发启动。②**卡片运行态标题修正**：`resolveRunningChildStepLabel` **跳过 tasks/intent/skill/decision/plan 脚手架步**（其 label 如「任务清单」非实时时间线活动，此前 running 首个子步恰是 todo 规划步导致误显）——只取首个 running **动态子步**（think→「深度思考」、tool→「执行命令」/「调用工具 x」、rag→「知识检索」、generate→「生成答复」）；SubagentCard **移除冗余 summary 行**「正在执行：{label}」（与任务名重复），`subagent-child-label` / `worker-child-label` 样式对齐原 summary 行（去「·」圆点、`flex:1` 截断、muted），卡片运行态主行只保留「任务名 + 实时子步标题」。§8.2 / §9.1 同步修订。
> **v17.16（2026-08-21）**：**卡片运行态显示阶段正文内容**——v17.15 卡片运行态跟的是子步标题（深度思考/执行命令），改为跟**阶段正文内容**：`resolveRunningChildStepBody` 取代 `resolveRunningChildStepLabel`——think/rag→`reasoning` 思考正文、tool→`detail` 工具输出，单行化 + 90 字符截断；**generate（最后正文）阶段固定显示「正在收尾回复」**（最终答复正文已在抽屉流式，卡片不再滚动）；正文为空回退子步 label / 阶段标题（深度思考/执行命令/知识检索）；仍跳过 tasks/intent/skill/decision/plan 脚手架步。SubagentCard / WorkerCard 变量更名 `childStepBody`，模板与 CSS 注释同步。§8.2 / §9.1 同步修订。
> **v17.17（2026-08-21）**：**Worker 内 spawn 子 Agent 卡嵌套进 worker 抽屉**——根因：`SpawnSubagentTool` 无条件 emit 到 Planner 主桥（`mainBridge`），Worker 内部 spawn 的子 agent 时间线卡平铺在主时间线正文，未按 v17.5 抽屉架构嵌套进父 worker 卡。修复：①`SpawnSubagentTool` 按当前执行上下文 `activeBridge` 计算 `emitTarget`——以 `worker-` 开头（Worker 内部 spawn）emit 到对应 worker 桥，经 `WorkerTimelineBridge.wrap` 折叠进 `worker-{taskId}.subSteps`（前端 `OperationStack` 非 DAG 时间线保留 subagent 步、`PlanNodeDrawer` worker 节点递归渲染 subSteps → 抽屉内嵌套显示子 agent 卡），否则仍直发 `mainBridge`（Planner/主 Agent 直接 spawn 行为不变）；②`SpawnRunRegistry.flushCancelTokens` 同 emitTarget 判定——Worker 内 spawn 的取消终态经 worker 桥折叠（抽屉内子 agent 卡更新为已取消），主 Agent spawn 仍直写 GenerationJob；③单测 `spawnInsideWorker_emitsSubagentCardToWorkerBridge` / `cancel_insideWorker_foldsTerminalIntoWorkerSubSteps` 覆盖，全量 1083 单测通过。
> **v17.18（2026-08-21）**：**Worker/子 Agent 运行态正文实时折叠 + taskQueue 独立下发 + flush 虚拟线程修复**——①**根因（阶段正文不显示的完整链路）**：v17.16 前端 `resolveRunningChildStepBody` 已按子步 `reasoning`/`detail` 取正文，但 `WorkerTimelineBridge.wrap` / `SpawnSubagentTimelineBridge.wrap` 仍显式丢弃 `reasoning` token（`token.isReasoning()` 直接 return），think 子步的 `reasoning` 从未折叠进 subSteps → 前端只能回退「深度思考」阶段标题（用户实测「只显示思考过程和工具调用步骤，无阶段正文」）；且 PLANNER 最终 generate 的 content 经 `GenerationFlushScheduler.flushPartial` 同步执行 `DesensitizeClient.scrub().block()`——LLM 流回调在 reactor 事件循环线程（Netty）触发时抛 `IllegalStateException`，`LoadBalancedWebClientTransport` SSE 被取消（实测日志「SSE cancelled after 2 events」），**PLANNER 正文/「正在收尾回复」流式中断**。修复：①两 Bridge 的 `wrap` 移除 `isReasoning()` 过滤——reasoning `step_delta` 经 `SubStepsFold.ingest` → `ProcessingStepMerger.applyDelta` 增量写入 think 子步 `reasoning`，随父卡 running 快照下发，前端实时显示思考正文；②`GenerationFlushScheduler.flushPartial` / `flushStepsPartial` 改 `Mono.fromRunnable(...).subscribeOn(VirtualThreadExecutors.scheduler())` 转投虚拟线程异步落库（`scrub` 的 block 不再落 Netty 线程）。③**TaskBoard 独立下发 taskQueue**：`StepMetadata` 新增独立 `taskQueue` 字段（`StepMetadataAssembler.withTaskQueue`，与 ReAct `tasks` 分离），`PlannerActionTool.emitTaskBoardSnapshot` 改发 `withTaskQueue`，`ProcessingStepSerde` 序列化落库；前端 `isHarnessBoard` 判定基于 `metadata.taskQueue`，TaskBoardPanel 任务名前加执行单元记号 `T1-1`（`formatTaskUnitId`），`taskboard-task-id` 样式与任务内容一致（去等宽加粗、统一 `--sun-font-sm`/`--sun-text-muted`/`line-height:1.45`）。§8.2 / §9.1 同步修订。
> **v17.19（2026-08-21）**：**抽屉右上角层级栈式退出**——v17.17 后 worker 抽屉内可下钻子 agent 卡（嵌套渲染），但右上角关闭按钮仍一次性关掉整个抽屉，无法逐层返回。修复：①`usePlanNodeDrawer` 新增**层级栈** `history`——`open(payload, { push })` 仅抽屉内嵌套卡（`provide('planNodeDrawerNested')` 标记，WorkerCard/SubagentCard inject 后传 `{ push: true }`）下钻入栈；主时间线 / DAG 画布平级打开重置为单层（新浏览上下文）；同一 planId 重复下钻替换栈顶防重；②右上角按钮按 `depth` 动态——深度 >1 显示**返回箭头**（title「返回上级」，`goBack()` 弹出栈顶恢复父层），深度 =1 显示原收起图标（`close()` 真正关闭）；③`goBack` 返回父层后 `PlanNodeDrawer.step` 经 `resolveAgentNodeStepForDrawer` 从最新消息 steps 兜底刷新（Worker/SubagentCard watch 仅刷新栈顶，父层不残留旧快照）。§8.2 / §9.1 同步修订。
> **v17.20（2026-08-21）**：**Worker 卡按任务序号稳定排序**——根因：`dispatch_worker` 并发派发时各 Worker begin 卡 `startedAt` 由各自线程取墙钟（毫秒级随机），且 auxiliary 直刷 GenerationJob 到达顺序不定；前端 `sortSteps` 按 `startedAt ?? ts` 全量重排 → 卡片初始顺序与 TaskBoard 任务序号（T1/T2/T3）不一致，运行期随到达顺序跳位。修复：`sortSteps` 对 `worker-*` 卡**优先按任务序号稳定排序**（taskId 首个数字：`worker-t1-1 → 1`、`worker-r5-quality-2 → 5`），同序号按执行版本升序（t1-1 < t1-2，历史保留在前、新执行在后），描述后缀无版本（t1-arch）视为无穷大排最后；worker 与 think/tool 混排仍按时间线时间戳（不插队）。单测 4 例覆盖并发乱序到达/版本号/描述后缀/混排。§9.1 同步修订。
> **编号**：阶段四增量（重建 Planner-Executor，删除动态 Plan-Workflow）
> **前置**：
>   - [统一资源路由 v6](./2026-07-29-unified-routing-design.md) — 用户三模式 `fast`/`pro`/`workflow` + 双轨意图收集 + `ResourceDispatcher`（**H-5 ✅**；R-4 = 阶段 D **✅**）；**命名四轴 SSOT**
>   - [ReAct 目标对齐 4.7.7](./2026-07-27-react-goal-alignment-design.md) — Middleware/完整 4.7.7 **仍设计态**；harness 内已有**机械薄实现** `plan.harness.GoalAlignmentValidator`（DEVIATED/STUCK，H-3；v17 Loop 不再调用，本字段保留但降级为可选审计）
>   - [多 Agent 统一设计](../2026-07-29-multi-agent-unified-design.md) — spawn_subagent 中心化编排 + `AgentRunRequest`
> **依赖与落地顺序（跨 spec）**：[specs/README.md §活跃增量方案](../README.md#活跃增量方案依赖与落地顺序2026-08-13) — 主链 `H-0～H-7 ✅ → H-8（v17 一次性 ReAct）✅ → 阶段 D ✅`。
> **现状基线（2026-08-21）**：H-8 ✅（v17 架构重构）；v17.3 Worker 正文流式下发父步 result（去除「任务结果汇总」子步）；v17.4 前端 worker 展开区去重（正文唯一由嵌套 stack contentBlocks 承载，result 不再渲染）；v17.4.1 折叠主行预览不再回退 result（`resolveStepSummaryFull` 对 worker 步返回空）；harness.worker v6 任务摘要改两列竖排（字段|内容）避免窄容器三列挤；**v17.5 Worker 迁移子 Agent 抽屉架构**（WorkerCard 点击开 PlanNodeDrawer；任务契约经 metadata.spawnPrompt 展示；正文收进抽屉不再主时间线穿插）；v17.6 Worker prompt 去除工具白名单枚举（运行时 toolWhitelist 控制注册）；**v17.7 Worker 并发流式 + 重试机制 ✅**（dispatch 强制 background + await_tool_run 收集；TaskItem 版本化 t1-1/t1-2/t1-3 + failReason 分类 + 重试上限 2 次）；**v17.8 并发/取消/工具 9 项修复 ✅**（并发上限 10 · worker await 独立参数 · Planner 补注册 await_tool_run + main run · 新增 task_status 工具 · 前端拼接执行单元记号 · Worker 取消 dispose 订阅终止流式 · 整体取消级联 Worker）；**v17.9 await_tool_run 批量等待 ✅**（runIds 数组一次收集同轮多任务，AsyncToolRunRegistry.awaitMany 共享观察窗口，planner.harness v4 引导批量）；**v17.10 并发流式根因修复 + 记号统一 ✅**（Worker 改用 `worker-{runId}` 独立 sessionId 解除 AgentScope per-instance callGates 串行——多 Worker 真正并行流式；前端 `formatTaskUnitId`：`r5-quality-2 → T5-2`，WorkerCard/TaskBoard 共用无边框记号）；**v17.11 版本记号首版归一 + 取消历史保留 + 全局取消乐观态 ✅**（`TaskItem.versionedId()` 首版即带版本 t1-1，TaskBoard/WorkerCard 统一 T1-1/T1-2 对应不覆盖；`mergeTaskQueue` 不覆盖 fail/cancelled 历史，`dispatch_worker` 重派自动版本化 t1-2；前端 stop 乐观标运行中 worker 卡已取消；`planner.harness` v6 重派语义 + timeout_sec 引导）；**v17.12 Spawn 子 Agent 并发流式 + 续跑保持已取消 ✅**（`AgentRole.SUB` 用 `subagent-{runId}` 独立 sessionId，同消息多 spawn 真正并行流式；`isResumableReactStep` 移除 subagent——全局取消标「已取消」的卡续跑保持终态，不复活为等待中）；**v17.13 异步等待契约统一 ✅**（`AwaitToolRunTool` 资格判定改 runId 作用域——MAIN/WORKER/PLANNER 可 await 自己派发的 exec/spawn/worker run，Worker 内 await 不再被拒；新增通用 `async_status(runIds[]/runId)` peek 工具全角色注册，替代 worker 专有 task_status 的跨角色误用；`DynamicToolkitFactory.buildForWorker`（SUB 基础 + await_tool_run/async_status/spawn_subagent）；`mode-overlay.react` v5 + `harness.worker` v10 统一 AsyncTool 段：批量等待 + 状态回查 + exec/spawn/worker 三档观察窗口 + 禁臆造工具名；线上 catalog=129）；**v17.14 Spawn 并发卡片即时显示 + 卡片运行态标题 ✅**（`resolveFlushMessageId` main run 反查——非 HITL main bridge 的 auxiliary（spawn begin 卡/worker 折叠快照）直刷 GenerationJob，根治主 Agent 工具调用 blockLast 阻塞期间 hookQueue 不 drain、并发 spawn 子 Agent 卡刷新才显示的卡顿；前端 `resolveRunningChildStepLabel`——SubagentCard/WorkerCard running 时任务名后跟随当前子步标题（深度思考/执行命令/调用工具 x），正文 shimmer 同步）；**v17.15 PRO 路径工具审计上下文绑定 + 卡片运行态标题修正 ✅**（`PlannerHarnessExecutor` 入口绑定 `StepEventBridge.bindToolAudit`——PRO 路径此前缺审计绑定，worker/planner 的 spawn_subagent 全被「缺少会话审计上下文」拒于门外，子 Agent 不启动、前端无卡；前端 `resolveRunningChildStepLabel` 跳过 tasks/intent/skill 等脚手架步只取动态子步、SubagentCard 移除冗余「正在执行：{label}」行、child-label 样式对齐 summary 去「·」）；**v17.16 卡片运行态显示阶段正文内容 ✅**（`resolveRunningChildStepBody` 取代 label 版——think/rag 显示 `reasoning` 思考正文、tool 显示 `detail` 工具输出，单行化 + 90 字截断；generate 最后正文阶段固定显示「正在收尾回复」；正文空回退阶段标题；仍跳过脚手架步）；**v17.17 Worker 内 spawn 子 Agent 卡嵌套进 worker 抽屉 ✅**（`SpawnSubagentTool` emit 目标按 `activeBridge` 判定——Worker 内 spawn 经 `WorkerTimelineBridge.wrap` 折叠进 `worker-{taskId}.subSteps`，抽屉内嵌套显示子 agent 卡，不再平铺主时间线正文；主 Agent 直接 spawn 仍直发 mainBridge；`SpawnRunRegistry.flushCancelTokens` 取消终态同 emitTarget 判定）；**v17.18 运行态正文折叠 + taskQueue 独立下发 + flush 虚拟线程 ✅**（`WorkerTimelineBridge`/`SpawnSubagentTimelineBridge` 的 `wrap` 移除 `isReasoning()` 过滤——think 子步 `reasoning` 增量折叠进 subSteps，卡片实时显示阶段正文/「正在收尾回复」；`GenerationFlushScheduler.flushPartial`/`flushStepsPartial` 转投 `VirtualThreadExecutors`——scrub 的 `Mono.block()` 不再落 Netty 事件循环线程，根治「SSE cancelled after 2 events」正文流式中断；`StepMetadata` 新增独立 `taskQueue` 字段 + `withTaskQueue`，`PlannerActionTool.emitTaskBoardSnapshot` 改发 taskQueue，`ProcessingStepSerde` 落库，前端 `isHarnessBoard` 基于 taskQueue、TaskBoardPanel 任务名前加 `T1-1` 记号且样式与任务内容一致）；Planner 一次性 ReAct run（`max-iters=60`）；Loop 仅做墙钟熔断；Worker dispatch 立即 emit in_progress TaskBoard 快照；harness 包 60/60 单测通过；**Live P1–P9 全绿**（`verify_planner_executor_live.py --suite all`；P5 重启恢复通过；P8 长墙钟默认 skip）。
> **命名**：① 本文 `PlannerHarness*` / `harness.*` 与 AgentScope 官方 `HarnessAgent`（Compaction 载体）**无关**；② 四轴见上表，**禁止**用 `scene`/`call_scene` 承载会话形态或调用点。
> **一句话**：**完全舍弃动态 Plan-Workflow**（Planner 一次性 DAG 规划 + PlanApproval 用户确认 + Plan DAG 时间线），重建为真正的 Planner-Executor——Planner 是 **一次性 ReAct 主 Agent**（全量上下文 + PlanNotebook 叠加），自带 `plan_submit / dispatch_worker / self_assess` 三个元工具表达动作；Worker 是 Planner 的**工具调用**（`forWorker()` 丰富上下文），与 Subagent 同构（独立 run + worker-{taskId} 一级卡）。Planner 停止调工具即开始正文输出 = 综合回答。**静态 Workflow（4.13 Studio 编排）保留**；新 Planner-Executor 时间线与普通 ReAct 同构：think + tool_call（plan_submit / dispatch_worker / self_assess）平铺，worker-* 一级行不缩进，**不**渲染 Plan DAG；worker 卡 v17.5 起复用子 Agent 抽屉展示正文/子步骤（点击打开 `PlanNodeDrawer`）。

| 轴 | 新名 | 废弃 |
|----|------|------|
| 会话形态 | `kind`（`chat`/`task`） | `scene` |
| 执行模式 | `executionMode` | — |
| 业务域 | `biz_scene`（暂不动） | — |
| LLM 调用点 | `callSite` / DB·MQ `call_site` | `call_scene` |

---

## 0. 架构决策记录（ADR 摘要）

| # | 决策 | 说明 |
|---|------|------|
| D1 | **完全舍弃动态 Plan-Workflow** | `WorkflowPlanner`（一次性 DAG 生成）+ `PlanWorkflowExecutor` + `PlanApprovalService` + Plan DAG 时间线全部删除 |
| D2 | **静态 Workflow 保留** | 4.13 Studio 编排的确定性业务流（`WorkflowExecutor` + `StaticPlanAdapter`）是已验收资产，与 LLM 动态规划正交 |
| D3 | **DAG 画布留存给静态 Workflow** | `PlanExecutionCanvas` / `PlanDagExpandLayer` / `usePlanDagExpand` 保留，仅服务静态 Workflow |
| D4 | **新 Planner-Executor 用分层普通时间线 + TaskBoard** | 正文复用 ReAct 式 `OperationStack` 时间线（层级折叠，**非**卡片墙）；看板一级=H1、二级=Worker todolist；不渲染 Plan DAG |
| D5 | **Plan Approval 完全不做** | 渐进式自驱执行；删 `PlanApprovalService` / `PlanApprovalActions` 及 **PlanApproval 对 `CollapsibleConfirmPanel` 的用法**；**保留**该组件供 HITL/Recovery/Decision 共用壳 |
| D6 | **复用 AgentRuntime 内核** | Planner = `AgentRuntime.run(PLANNER)`（**独立角色**，非 MAIN）；Worker = `AgentRuntime.run(WORKER)`（新角色）；子 Agent = `AgentRuntime.run(SUB)`。现有 `PlannerAgentRuntime`（一次性 `WorkflowPlanner`）须**重写语义**为 ReAct + H1，非零改复用 |
| D7 | **复用审计通道** | `PlanExecutionAuditService` 事件通道复用，新增 `plan.worker_*` 事件 |
| D8 | **终态复用 ExecutionPlanStatus 枚举** | `completed / completed_with_errors / failed / rejected / degraded_react`，不新建状态机 |

---

## 0.1 简化决议（v2 · 2026-08-05）

> 依据：企业级智能中台定位（服务 B/C 端、对话+任务双场景）下对 v8 详设的逐项冗余评估（逐项实证核对见 §11）。**核心原则：Planner 复用 AgentScope 官方能力与既有管线，不重复造轮子**。

| # | 决议 | 说明 | 对应 v8 设计 |
|---|------|------|------------|
| **S1** | **砍独立 Evaluator**（TaskEvaluator / GoalEvaluator 不实现） | Chat/Task 统一 **Planner 自判**（`selfAssess`），省每 task 一次 LLM 调用与 2 个 prompt + `harness_eval_result` 表。真实代价：长语义任务无 Maker-Checker 防确认偏误——由用户反馈 + GoalAlignmentValidator 机械校验兜底 | v8 §4.4 |
| **S2** | **持久化降级 Redis 单写** | `PlanNotebookStore` 仅 Redis save/load/delete/renewTtl；**删** `PlanNotebookMysqlWriter` / `PlannerNotebookEntity` / `PlannerNotebookRepository`、`planner_notebooks` 表 DDL、version 幂等重放。冷审计职责由既有 `PlanExecutionAuditService`（RocketMQ/MySQL/ES）覆盖 | v8 §5.1 |
| **S3** | **去 Tier 0/1/2 形式化分层与压缩点基建（仅 H1）** | run 内压缩由 AgentScope 官方 `CompactionMiddleware`（已落地）负责；跨轮 L1 压缩由既有 `L1Compressor` + `far_folded_msg_ids` + **压缩点模式**（[五层 spec §5.5/§13.3](../2026-07-31-unified-context-compression-design.md)）负责，**保持不动**；H1 仅作为 `injectedBlocks` 固定注入 query 前，rounds 超阈值时简单截断为摘要。**不新建**压缩点 / last_folded_round / 幂等 upsert | v8 §2.3.4/§2.4 |
| **S4** | **砍 P2 `PlanSharedMemoryStore`** | WorkerContextFactory 从 H1 rounds 按 taskId/dependsOn 读已完成 handoff 注入，不建第三份状态 + KV 红线规则 | v8 §2.5.1 |
| **S5** | **取消分解模式枚举 → 单一循环**（v4 定稿；取代「三态→两态」） | **不设** `full` / `hierarchical` / `incremental`、`taskDecomposition`、`completeness`、强制「阶段骨架→阶段细拆」协议、`planner.phase` / `callSite=plan-phase`（旧称 call_scene）。引擎只跑：**Plan（吐调度单元）→ Validate → Execute Workers → selfAssess → replan/done**。信息不足时 Planner 自然排调研步，handoff 后按 S6 重规划；**细则（文件/命令级）在 Worker 内 ReAct**，Planner 只吐可调度粗单元（可并行 / `dependsOn` / 写 H1）。高不确定探索由用户选 **快速 `fast`**（ReAct），**非** harness 内模式分支、**非** L3 自动改道（[routing v6](./2026-07-29-unified-routing-design.md)） | v8 §0.2/§4.1 |
| **S6** | **重规划收敛** | 5 类触发 → **3 类显式**（①连续失败 ③目标变更 ④进度偏差）+ 预算熔断（maxRounds/maxDuration）；②信息缺口由「调研步 + 自然重规划」承接（不再绑阶段切换协议）；⑤资源溢出折叠为熔断。删 plan-similarity 语义去重（`max-replans` 已兜底）；**保留** GoalAlignmentValidator 的 DEVIATED/STUCK | v8 §5.2.2 |
| **S7** | **harness 不复用 PlanValidator** | `PlanValidator` 校验 BPMN/DAG 硬契约（节点 type 白名单、answer 强制、网关拓扑），与 harness 线性 task 队列语义不符。harness 用轻量结构校验（id/label/依赖环）；PlanValidator 留给静态 Workflow | v8 §4.2 |

**保留不变**：会话形态 `kind`（`chat`/`task`，用户显式；与 `executionMode` 正交，§6.1）；PlanNotebook (H1) 跨轮记忆；Planner/Worker 职责分离 + `forWorker()` 丰富上下文 + toolWhitelist 下发；handoff 双写（L1 尾部 + H1）；超时/重试/熔断预算（**初值见 §8.1 长负载档**）；降级通道（Planner 全失败 → React 兜底）；复用 AgentScope StateStore / AgentRuntime / 审计 / 沙箱 / spawn_subagent。  
**GoalAlignment**：harness 包内机械薄实现 ✅（`staleRounds` / 完成度启发式）；完整 [4.7.7](./2026-07-27-react-goal-alignment-design.md) Middleware / ReAct 共用层 ✅（2026-08-26 落地，默认关）。

---

## 1. 背景与问题

### 1.1 现状：动态 Plan-Workflow 是「简易 Workflow 动态版」

当前 `PLAN_WORKFLOW` 执行模式的本质缺陷：

```
用户输入
  → L0/L1/L2 路由命中 PLAN_WORKFLOW
  → WorkflowPlanner（一次性 LLM 调用）生成 PlanJson（nodes + edges DAG）
  → PlanValidator 校验 + Replan（≤2 次）
  → PlanApprovalService 等用户确认
  → PlanMaterializer + PlanNormalizer 物化为可执行 DAG
  → WorkflowExecutor 拓扑调度执行（rag/tool/agent 节点）
  → 静态 DAG 时间线展示（PlanWorkflowPanel + PlanExecutionCanvas）
```

**问题**：
1. **规划一次、执行到底**：Planner 只在开头做一次全量 DAG 规划，执行期不能改图（`NodeRetryExecutor` 只做节点级重试）。长任务的信息缺口无法在规划期预见，DAG 无法自适应。
2. **Planner 不是 Agent**：`WorkflowPlanner` 是**一次性 LLM 调用**（`/chat/completions` 返回 JSON），不是 ReAct 主 Agent。它没有 think→tool→observe 循环，无法在规划中使用工具补信息。
3. **用户确认是约束而非特性**：PlanApproval 阻塞执行等待用户确认，与「Agent 自主推进」的体验矛盾；`approval.on-timeout=fallback_react` 说明确认机制本身是负担。
4. **DAG 时间线展示与执行模型耦合**：前端 `PlanWorkflowPanel` 假设「先全量 DAG，再逐节点执行」，无法表达「边执行边重规划」。
5. **维护成本**：`WorkflowPlanner` + `PlanMaterializer` + `PlanNormalizer` + `PlanApprovalService` + `PlanTimeline` 等 20+ 类专为「一次性 DAG」服务，与真正的 Planner-Executor 需求大部分不匹配。

### 1.2 目标：真正的 Planner-Executor

对齐业界共识（Devin / Claude Code / Cursor Agent Swarm）：

```
Planner（ReAct 主 Agent）—— 只规划、决策、综合，不执行
  │  think → worker-1(工具调用) → observe(handoff) → think → ...
  ▼
Worker（Planner 的工具调用）—— 只执行、不规划，拥有 forWorker() 丰富上下文
  │  内部 ReAct 循环 + 可 spawn 真正隔离的子 Agent
  ▼
handoff 双写：H1 PlanNotebook + Planner L1 尾部（视同 tool_result）
  ▼
Planner 自判（selfAssess，S1 统一，无独立 Evaluator）→ Planner 决策 → 综合回答
```

核心差异 vs 旧 Plan-Workflow：

| 维度 | 动态 Plan-Workflow（删除） | Planner-Executor（新建） |
|------|--------------------------|--------------------------|
| 规划 | 一次性全量 DAG（`WorkflowPlanner`） | **单一循环**边走边规划（`HarnessPlanner` = ReAct；信息不足先调研再重规划） |
| Planner 本质 | 一次性 LLM 调用 | ReAct 主 Agent（全量上下文 + H1） |
| 执行 | DAG 物化 + 拓扑调度（`WorkflowExecutor`） | Worker = 工具调用（`forWorker()`） |
| 重规划 | 校验失败 Replan（≤2 次），执行期不能改图 | **3 类显式触发**式重规划（失败重试耗尽 / 目标变更 / 进度偏差）+ 预算熔断 |
| 用户交互 | PlanApproval 强制确认 | 渐进式自驱，follow-up 重定向 |
| 时间线 | Plan DAG 画布 | 分层普通时间线 + TaskBoard（§4） |
| 持久化 | `execution_plan` 表（plan-workflow 部分） | **PlanNotebook Redis 单写**（会话级跨轮记忆） |

---

## 2. 资产处置清单

### 2.1 保留（复用，零改动或小改）

| 资产 | 用途 |
|------|------|
| `AgentRuntime.run` / `ReActAgentRuntime` / `PlannerAgentRuntime` | 统一执行内核；Planner = **独立 `AgentRole.PLANNER`**（**重写**现有一次性规划实现 → ReAct + H1；非 MAIN、非零改） |
| `ReactExecutor` | 用户选 `fast` 时的普惠层 |
| `WorkflowExecutor` + `StaticPlanAdapter` + `WorkflowCheckpoint` | **静态 Workflow**（4.13 确定性流程） |
| `PlanValidator` + `PlanExecutionSchedule` | **仅静态 Workflow**（S7：harness **不**复用 PlanValidator；用轻量 id/label/依赖环校验） |
| `NodeRetryExecutor` + `NodeRetryPolicyResolver` | 重试语义，抽象出「S 域任务级重试」接口供 `taskRetryMax` 复用 |
| `PlanExecutionAuditService` | 审计通道，新增 `plan.worker_*` 事件 |
| `ExecutionPlanStore` / `ExecutionPlanRepository` | **仅静态 Workflow** 使用（`StaticPlanAdapter` 快照） |
| 工具链全链路 | `CatalogRemoteAgentTool` / `RagTool` / 沙箱 / `spawn_subagent` |
| 前端 `PlanExecutionCanvas` / `PlanDagExpandLayer` / `usePlanDagExpand` | **仅服务静态 Workflow**（D3） |
| `OperationStack` / `TaskBoardPanel` / `SubStepsFold` | 普通时间线 + 看板；harness 复用并做层级扩展 |
| `CollapsibleConfirmPanel` | **保留** HITL/Recovery 共用壳；仅断开 PlanApproval 绑定（D5） |
| `PlanMaterializer` / `PlanNormalizer` | **静态 Workflow** 快照解析/规范化（`WorkflowDefinitionLoader` / `WorkflowResumeService` / `ExecutionPlanStore` / `PlanAnswerPromptAssembler`）；阶段 D 核对后保留 |
| `PlanTimeline` | **静态 Workflow / harness** 的 plan 步骤与 fallback 工具（`WorkflowStaticPlanRunner` / `PlannerHarnessExecutor`）；阶段 D 核对后保留 |
| `PendingInteraction` / `ResumeInteractionHint` | **HITL / Recovery** 等待态复用（`HitlConfirmationService` / `WorkflowNodeRecoveryService` / `WorkflowNodeRunner`）；仅断开 plan 确认绑定，阶段 D 核对后保留 |

### 2.2 舍弃（随动态 Plan-Workflow 一并删除）

| 资产 | 说明 |
|------|------|
| `ExecutionMode.PLAN_WORKFLOW` 路由入口 | 语义路由不再产生 PLAN_WORKFLOW（**已删**：`ExecutionMode` 收敛为 `FAST/PRO/WORKFLOW`） |
| `WorkflowPlanner` | 一次性 DAG 生成 LLM 调用（**已删**） |
| `PlanWorkflowExecutor` / `PlanWorkflowPlanningRunner` / `PlanWorkflowResumeRunner` | 动态 DAG 编排（**已删**） |
| `PlanApprovalService` / `PlanApprovalUserAction` / `PlanApprovalDecision` / `PlanApprovalRound` / `PlanApprovalWaitResult` / `PlanApprovalRejectedException` | 确认机制（D5；**已删**） |
| `WorkflowPlanner` 的 `planner.prompt` Catalog | 一次性规划 prompt |
| golden set §A（PLAN_WORKFLOW 用例） | 迁移到 harness/ReAct 语义 |
| 前端 `PlanApprovalActions` + PlanApproval→`CollapsibleConfirmPanel` 绑定 | 确认 UI（D5；**不**删共享 Confirm 壳） |
| 前端 `/plans/:planId` 页（plan-workflow 专属部分） | 动态 plan 详情 |
| `execution_plan` 表中 plan-workflow 生成的行 | 静态 Workflow 快照仍使用该表 |
| `PlanNotebookMysqlWriter` / `PlannerNotebookEntity` / `PlannerNotebookRepository` / `planner_notebooks` DDL | **S2：持久化降级 Redis 单写** |
| `PlanNotebookRecoveryService` | **S2：恢复复用 AgentScope StateStore 既有 checkpoint** |
| `GoalEvaluator` / `TaskEvaluator` / `harness_eval_result` | **S1：统一 Planner 自判** |
| `PlanSharedMemoryStore` (P2) | **S4：从 H1 rounds 读上游 handoff** |

### 2.3 新增（H-0～H-6 ✅；H-7 代码 ✅ / Live 待部署；阶段 D 源码删除 ✅）

| 组件 | 用途 |
|------|------|
| `PlannerHarnessExecutor` | ResourceDispatcher 入口；`executionMode=pro` 进入；记忆闸门按 `kind`（chat/task） |
| `PlannerHarnessLoop` | 单一循环编排引擎（Plan→Execute→Assess + 超时/重试/Stuck） |
| `HarnessPlanner` | 按现有信息吐调度单元 + 3 类触发式重规划 + selfAssess + 综合回答（**无**分解模式自判） |
| `PlanNotebook` (H1) | 跨轮共享工作记忆 POJO |
| `PlanNotebookStore` | **Redis 单写**（save/load/delete/renewTtl） |
| `WorkerContextFactory` | `AssembledContext.forWorker()` 构造，从 H1 rounds 读上游 handoff |
| `GoalAlignmentValidator` | 目标对齐校验（DEVIATED/STUCK，机械） |
| `AgentRole.WORKER` + `AgentRunRequest.worker()` + `AssembledContext.forWorker()` | Worker 角色 |
| harness 分层时间线层级 + TaskBoard 一/二级投影 | 见 §4（v5） |

> **S1/S4 裁撤**（相对 v8）：独立 `GoalEvaluator` / `TaskEvaluator` / `PlanSharedMemoryStore` / `harness_eval_result` **不实现**；`PlanNotebookRecoveryService` **不实现**（恢复 = Redis load + AgentScope StateStore 既有 checkpoint 续跑）。

---

## 3. 架构总览

### 3.1 三层 Agent 角色

| 层级 | 角色 | 上下文 | 能力 |
|---|---|---|---|
| L0 | Planner = ReAct 主 Agent（**`AgentRole.PLANNER` 独立运行态**，`PlannerAgentRuntime` 实现；全量 ReAct 能力 + H1 PlanNotebook） | 全量：L1 + L2 + H1 PlanNotebook（稳定前缀在前、H1 固定 query 前，S3）；L1 组装**与普通 ReAct MAIN 完全一致**（v3 决策：复用 `ContextAssembler.assemble`，chat 含 L3 召回） | 规划、调度 Worker、自判决策、综合回答 |
| L1 | Worker = Planner 的**工具调用**（`AgentRole.WORKER`） | `forWorker()`：稳定前缀 + taskGoal + constraints + query（v3 决策：**不注入 L2 用户画像**；工具白名单经 `AgentRunRequest.toolWhitelist` 运行时控制，不写入 prompt） | ReAct 自主循环、内部 spawn 子 Agent |
| L2 | 子 Agent（`AgentRole.SUB`） | `forSubAgent()=empty()`：仅 spawn prompt → 输出 | 单次执行，最严格隔离 |

### 3.1.1 Planner/Worker 上下文契约（v3 定稿 · 2026-08-07）

> 对齐 [压缩点模式（五层 spec §5.5）](../2026-07-31-unified-context-compression-design.md) 与 task/chat Near 差异（v14/v15）。核心结论：**只有 Planner 带跨轮压缩点包袱，Worker/子 Agent 不用**。

| 角色 | 上下文构成 | 压缩处理 | 生命周期 |
|------|-----------|----------|----------|
| **Planner** | `ContextAssembler.assemble(chat_message 历史)`（L2 + Far + Mid + Near + L3 + guide，按 `kind` 走 v14/v15 Near 规则）**+ H1 注入块（query 前 injectedBlock）** + Worker handoff（run 内，视同 `tool_result` 追加 L1 尾部） | 跨轮：既有 `L1Compressor` + `far_folded_msg_ids` 压缩点模式（§5.5.4①，Near 只增、80%/40 轮触发前移一次）；run 内：AgentScope `CompactionMiddleware`（handoff 大结果先 `ToolResultEviction`） | 会话级（多轮 run 共享 L1 + H1） |
| **Worker** | `forWorker()`：**稳定前缀**（`harness.worker` 模板 + taskGoal/constraints/expectedOutput/successCriteria，同一 plan run 内字节不变；工具白名单由 `AgentRunRequest.toolWhitelist` 运行时控制注册，**不写入 prompt**）+ **动态段**（upstreamResults 按 `dependsOn` 定向 + query） | **不做 L1 压缩点**（单任务用完即毁）；内部 ReAct 循环用 AgentScope `CompactionMiddleware` + `ToolResultEviction`（S 域有界，§2.5.5） | 单任务，结束即销毁 |
| **子 Agent** | `forSubAgent()=empty()`：仅 spawn prompt（任务描述 + 输入） | 无（最严格隔离） | 单次执行 |

**三条注入红线（KV 缓存）：**
1. Worker handoff **不落 `chat_message`**，只进 H1 + run 内 L1 尾部；跨轮 Planner 新 run 的 L1 历史 = 普通 user/assistant 对话（`loadHistory`），Worker 结果认知靠 H1 重建
2. Worker 的 `upstreamResults` 只渲染 **动态段（query 附近）**，禁止写入稳定前缀——否则每个 Worker 前缀字节不同，跨 worker 前缀复用全失效（v8 §2.5.3 规则 6）
3. H1 注入块固定 `query 前`（= `PromptComposer.appendReactInjectedContexts` 现有注入点），**零新增机制**；Worker handoff 在 Planner L1 天然 tail append，不重排 Near/Mid（v8 §2.3.3）


### 3.2 执行流程（v17 一次性 ReAct run）

```
用户选择 executionMode=pro + kind + RoutingResult（轨 A：agentIds/skillIds）
  → PlannerHarnessExecutor
  → PlannerHarnessLoop.run(notebook, ctx)
      → HarnessPlanner.runPlanned(notebook, ctx)
          └─ AgentRuntime.run(AgentRole.PLANNER)
             └─ Planner 一次性 ReAct run（max-iters=30；AgentScope 官方 ReAct 循环）
                ┌──────────────────────────────────────────────────────────┐
                │ ① 模型思考 → emit think                                       │
                │ ② plan_submit(tasks[])         ← AgentTool 提交调度单元       │
                │ ③ dispatch_worker(taskId)       ← AgentTool 异步派发 Worker（v17.7 强制 background）
                │    ├ 立即返回 {runId, taskId}，Planner ReAct 不阻塞
                │    ├ Worker 独立 ReAct run (worker-{taskId} 一级卡，并发流式)
                │    └ await_tool_run(runId) 收集 handoff（超时/失败/取消分类返回）
                │ ④ 可选：self_assess(goalCompletion, nextDirection, reason)     │
                │ ⑤ 模型停止调工具 → 输出 content tokens → 主时间线正文（综合回答）│
                └──────────────────────────────────────────────────────────┘
             └─ run 结束（max-iters 耗尽 / 自然完成 / 异常）
      → 墙钟熔断（max-duration-ms）兜底；doFinally 落盘 store.save
```

**关键性质（与 v15/v16 区别）**：
1. **Planner 只一次 run**：旧 Loop 拆 `planNext + selfAssess + synthesizeAnswer` 三次独立 ReAct run，每次 run 都要重置上下文、注入 notebook摘要、判定 ActionSignals；本版三合一为**一次连续 ReAct run**，LLM 在同一会话历史内完整看到 think + tool_call + handoff。
2. **Planner 与 Worker run 之间无 Loop 介入**：Worker 的 handoff 作为 `tool_result`（经 `await_tool_run` 收集）回到 Planner L1 尾部，**Planner 下一轮 think 可以引用 handoff 原文**（旧架构下 Planner 只看 notebook 压缩摘要）。v17.7 起 dispatch 异步化，同波多 Worker 可并发流式输出。
3. **Planner 自决何时收束**：旧 Loop 强制每波后做 selfAssess；本版 self_assess 是**可选元工具**，Planner 可以直接 think → 停止调工具 → 输出 content，self_assess 不再是流程必经节点。
4. **Worker dispatch 立即 emit TaskBoard in_progress 快照**：`WorkerDispatchTool.replaceTaskStatus("in_progress")` 后立即 `emitTaskBoardSnapshot("worker-start")`——前端 TaskBoard 在 Worker 执行期间显示 → 箭头（in_progress），完成后 `emitTaskBoardSnapshot("worker-done")` 切换为 ✓。
5. **Loop 仅做熔断**：`PlannerHarnessLoop.run` 退化为 ~70 行——启动 Planner run + `applyWallClockGate(maxDurationMs)` + `doFinally store.save`，无 wave / `executeTaskWithRetries` / `resolveAssessDecision` / `hasNoOutstandingWork` / GoalAlignmentValidator 调用。

> **动作协议（v17 终稿）**：Planner 的动作一律经 AgentTool 表达：
> - `plan_submit(tasks[])`：覆盖/合并 taskQueue（旧任务保留，被新一轮规划撤下的标 `obsolete`）。
> - `dispatch_worker(taskId)`：**v17.7 起强制 `background=true`**（异步派发，立即返回 `{runId, taskId}`，Planner ReAct 不阻塞）；Worker 失败/取消后**不原地重试**--由 Planner 下一轮 think 决策重派（同任务版本 +1，最多 3 次执行）或改派新任务。
> - `self_assess(goalCompletion, nextDirection, reason)`：**可选**——Planner 想显式汇报决策时调；不调用不代表「未自判」，代表「通过停止调工具 + 输出 content 自然收束」。
> - 终态：停止调工具 → 输出 content → 主时间线正文 = 综合回答。
> **不解析模型正文 JSON**；`DispatchSession.ActionSignals` 字段保留（兼容/审计），v17 不再依赖其驱动循环。

> **Worker 执行行折叠（v15 保留 · v17.3 补 content 路由）**：Planner 直接经 `dispatch_worker` 调度 Worker 时，`WorkerDispatchTool` 经 `WorkerTimelineBridge`（与 `SpawnSubagentTimelineBridge` 同构）在主时间线发射一级 `worker-{taskId}` 卡（`phase=worker`），Worker 内部 think/tool 经 PASS_THROUGH wrapper 折叠为该行 `subSteps`；**Worker 正文 content 三类 token 同样经 wrapper 进 `bridge.wrap` 的 `SubAgentContentTokens.route` 流式下发父步 result**（v17.3 前入口漏判被丢弃）。v17 起 Planner session 持续存在到 run 结束，**fold 始终生效**——Loop 不再有兜底路径发射骨架，避免双写。

> **高不确定开放探索**：用户显式选 **快速 `fast`** → ReactExecutor（含 spawn/taskboard/沙箱）；**不**在 harness 内再设模式分支，**不**由 L3 自动改道（S5 + routing v6）。

### 3.3 单一循环与职责边界（S5 v4 + v17 强化）

**引擎不设分解模式，不驱动循环。** 无 `taskDecomposition` / `completeness` / full|hierarchical 自判；无强制「阶段骨架 → 阶段细拆」二次协议；无 `wave` / `executeTaskWithRetries` / `resolveAssessDecision` 引擎编排。

| 角色 | 吐什么 | 不吐什么 |
|------|--------|----------|
| **Planner**（一次性 ReAct run） | 可调度粗单元（里程碑/调研/执行步）+ `dependsOn` + 约束/成功标准（经 `plan_submit` 工具提交，写 H1）；可选自判 `self_assess`；**停止调工具直接输出 content** = 综合回答 | 文件级/命令级细则；full/hier 模式标签；正文 JSON / 伪通道标签；硬编码 think_summary 文案 |
| **Worker**（Planner 的工具调用；独立 run） | 单元内 ReAct：工具选择、试错、细则展开；任务摘要回传（tool_result 进 Planner L1 尾部 + 写 H1 `RoundRecord`） | 全局重规划（那是 Planner 的事） |

**自然过程（Catalog 引导，非引擎枚举）**：
1. Planner 首轮 think → 排大致步骤（信息不足可先派调研 Worker）
2. dispatch_worker 触发 Worker run（**异步 fire-and-forget，立即返回 runId**，v17.7），Planner ReAct 继续派发或调 await_tool_run 等待
3. await_tool_run 的 handoff 作为 tool_result 进 Planner 历史 → Planner 下一轮 think 看到真实结果
4. Planner 决定：再 plan_submit / 再 dispatch / self_assess / 停止调工具输出 content
5. content tokens 直接流到主时间线 = 综合回答
6. ReAct run 自然结束（max-iters 兜底或 content 已输出）

Planner LLM 调用统一 `callSite=plan`（强弱模型若需分层，走 phase5 5.3，**不**绑分解模式）。**不建** `planner.phase` / `callSite=plan-phase`。

**与 v15/v16 关键差异**：
- 旧：Loop 拆 3 次独立 LLM run + 引擎编排；Planner 决策依据 notebook 压缩摘要。
- 新：1 次连续 LLM run + ReAct 自然循环；Planner 决策依据完整 tool_result（含 Worker handoff 原文）。

#### 3.3.1 Worker 状态机与重试机制（v17.7）

> **背景**：v17.5 前多 Worker 并发时 token 被 `blockLast()` 缓冲、无法并发流式输出；且 Worker 失败/取消后无重试语义（v17 删除 `executeTaskWithRetries` 后完全依赖 Planner 自觉重发，无历史记录、无上限约束）。

**A. 并发流式（dispatch 强制后台化）**

`dispatch_worker` **强制 `background=true`**（代码层控制，无参数可选）——与 spawn_subagent 的 `background` 机制完全复用：

1. `WorkerDispatchTool` 内部 `Flux.subscribe()` fire-and-forget 启动 Worker run，**立即返回 `{runId, taskId}`**，Planner ReAct 不阻塞；
2. Worker token（think/tool/content）经 `WorkerTimelineBridge` **并发流式**下发（各 Worker 独立 `worker-{taskId}` 卡 + 抽屉，互不缓冲）；
3. Planner 后续经 `await_tool_run(runIds[] / runId)` 收集 handoff（超时/失败/取消分类返回）；**v17.9 起支持多 runId 批量等待**——同轮派发的多个 runId 用 `runIds` 数组一次收集（共享观察窗口，返回 `runs[]`，未完成含 running 快照），不再逐任务串行等；单任务仍可传 `runId`；
4. Worker 内部仍是完整 fast ReAct（可调工具、可 spawn 子 Agent、可有自己 todo）——不是简单 subagent。

**B. 失败/取消不原地重试——版本化 taskId**

失败/取消的执行记录**保留**，重试生成**新 taskId**（后端记号，非模型生成）：

```
t1-1 (failed: timeout)  → Planner 决定重派同任务 → t1-2 (failed: error) → t1-3 (done ✓)
t2-1 (cancelled: user)  → Planner 决定改派不同任务 → t3-1（全新任务链）
```

| 规则 | 约束（代码层硬控制） |
|------|---------------------|
| **重试上限** | 同一 baseTask 最多**重试 2 次**（总执行 3 次）；第 3 次仍失败 → 派发拒绝，Planner 必须改派新任务或收束 |
| **taskId 记号** | `{base}-{retryIndex}`（t1-1/t1-2/t1-3）；首次 retryIndex=1；**由后端 `TaskItem` 派生**，模型无感知 |
| **重派触发** | **无自动重试**——失败/取消后由 Planner 下一轮 think 决策（重派同任务 / 改派新任务 / 收束） |
| **历史保留** | 失败/取消的 TaskItem 不删除、状态不回滚；`parentTaskId` 指向上一执行 |

**C. failReason 分类**

| failReason | 含义 | 触发 |
|-----------|------|------|
| `timeout` | 超时 | Worker run 超过 `harness.worker.max-duration-ms` 墙钟 |
| `error` | 程序性失败/异常 | Worker run 抛异常、工具链路异常、LLM 网关异常 |
| `cancelled` | 用户取消 | 用户点击 WorkerCard 取消（`SpawnRunRegistry` 同构，单独取消不伤 Planner） |

**D. TaskItem 模型扩展**

```java
class TaskItem {
    String taskId;        // 完整记号 t1-2
    String baseTaskId;    // 基础任务 t1（重试链共享）
    int retryIndex;       // 第几次执行（1 起）
    String parentTaskId;  // 上一执行（t1-2.parentTaskId = t1-1；首次 null）
    String failReason;    // timeout | error | cancelled（仅失败/取消态）
    TaskStatus status;    // pending | in_progress | done | failed | cancelled
    // …其余字段不变
}
```

**E. 中断续跑**

整体中断（刷新/重启/全局停止）时 in_progress 的 Worker 随 PlanNotebook 落盘标记 `interrupted`；续跑后 Planner 经 handoff 缺失感知中断，可重派（同任务版本 +1 或新任务）——复用 v17 既有 store.save/恢复机制，无新增持久化结构。

**F. TaskBoard 显示规则**

- 每次执行独立一行（t1-1 / t1-2 / t1-3），**只显示任务名**（不显示记号，记号是后端审计字段）；
- 失败/取消行显示 **⊗（圆圈内 ×）**，done 显示 ✓，in_progress 显示 →；
- 失败/取消行**不清除**、不回滚状态，历史链完整可见。

**G. Worker 单独取消（v17.7 落地细节）**

- 后端 `WorkerTimelineBridge` 构造接收异步 runId，父步 metadata 携带 `workerRunId`（`StepMetadata` 新增字段，`buildMetadata()` 统一装配，与 `spawnPrompt` 并存）；
- 前端 `WorkerCard` 在 running 且 `metadata.workerRunId` 非空时显示取消按钮，`onStop` 调 `cancelSpawnSubagent(runId, step.id)`——`chatSessions.cancelSpawnSubagent` 新增可选 `stepId` 参数做乐观更新匹配（worker 步 id 为 `worker-{taskId}`，与 subagent 的 `subagent-{runId}` 前缀不同），请求仍统一走 `POST /generations/{id}/subagents/{runId}/cancel`（后端 `GenerationController.cancelSubagent` 对 subagent/worker 通用，均为 `SpawnRunRegistry` 按 runId interrupt）；
- 取消后 `WorkerCard` 展示 paused 黄标（「已取消」），TaskBoard 对应行由 `HarnessTaskBoardProjector` 映射为 `cancelled` 显示 ⊗。

### 3.4 v17 取消的旧引擎补丁（防止回潮）

| 旧引擎逻辑（v15/v16） | 新架构处置 | 取消理由 |
|----------------------|------------|----------|
| `PlannerHarnessLoop.run` 的 wave 循环 | 删除 | Planner 是 ReAct Agent，自身循环即可；Loop 加 wave 是冗余 |
| `executeTaskWithRetries(task)` | 删除 | Worker 失败由 Planner 看到 handoff 后自然 plan_submit 重发；**v17.7 新增**版本化重试（t1-1/t1-2/t1-3，最多 3 次执行）替代旧 task 内重试，保留历史记录 |
| `resolveAssessDecision(nextDirection)` | 删除 | self_assess 不再是流程必经节点；Planner 停止调工具即收束 |
| `hasNoOutstandingWork`（强制 ANSWER） | 删除 | Planner 自己知道何时没有更多任务可派；引擎强制判断属于倒置 |
| `GoalAlignmentValidator` 在 Loop 中调用 | 降级为可选审计 | Planner 完整看到 tool_result 后自带反馈闭环；GoalAlignment 机械判断反而引入脱节风险 |
| `tasksSnapshot` 在 wave 末尾 emit | 改为 Worker dispatch `in_progress` 时立即 emit | 解决"Worker 执行期间 TaskBoard 没 → 箭头"问题 |
| `plannerAnswer` 独立时间线步 | 删除 | Planner content 即综合回答，与正文同走 content 流，不需独立步 |

---

## 4. 前端约定（分层普通时间线 + TaskBoard，D4 · v5）

> **原则**：看板管「待办结构与进度」；正文时间线管「执行过程与 handoff」。二者职责分离，互不收束对方的数据。

### 4.1 正文时间线（普通时间线，非卡片 · v17 终稿）

形态对齐现有 ReAct `OperationStack`：**行式步骤时间线**，**平铺不缩进**（worker 行与 Planner think/tool 同级左对齐，与工具折叠思想一致）；**v17.5 起 worker 卡复用子 Agent 抽屉**（`WorkerCard` 点击打开 `PlanNodeDrawer`，正文/子步骤/任务契约全部进抽屉，不再展开区内嵌）。

```
intent
think (Planner)                              ← Planner ReAct 第一轮 think
plan_submit (元工具步，中文名)                  ← Planner 调 plan_submit（与普通工具调用同构）
think (Planner)                              ← Planner 看到 tool_result 后下一轮 think
dispatch_worker (元工具步，中文名)              ← Planner 调 dispatch_worker；Worker 开始
worker-{taskId}                              ← 一级行（与 think 同级）
 ├ think / tool-* / …                         ← Worker 内过程（subSteps，展开无缩进）
 ├ content（Worker 正文流式）                   ← Worker 正文经 content 路由流式下发抽屉（v17.3 / v17.7 并发）
think (Planner)                              ← Planner 看到 handoff 后继续 think
self_assess (元工具步，中文名，可选)             ← Planner 调 self_assess（可选）
think (Planner)                              ← Planner 决定收束
content tokens                              ← Planner 正文 = 综合回答（流到主时间线）
```

| 约定 | 说明 |
|------|------|
| 层级 | 一级平铺：`intent` / Planner `think` / Planner 元工具步 / `worker-*` / content；二级：Worker 内 think/tool/spawn（`SubStepsFold`），展开渲染**无缩进线** |
| 不折叠 | harness（pro）时间线**不做**工具组折叠（`groupToolSteps`）与多轮折叠（`roundGroupSteps`），所有过程步/工具步平铺（v16 保留） |
| 元工具行 | `plan_submit` / `self_assess` / `dispatch_worker` 等 tool 步平铺展示：中文名（`ToolCatalogService` 内建表）+ 专属图标（`tool-plan-submit` / `tool-assess` / `tool-dispatch`）（v16 保留） |
| 取消的旧步骤 | ~~`plan(R{n})` 独立步骤~~ / ~~`planner-answer` 独立步骤~~ / ~~"评估进展" "提交调度计划" "综合回答 Planner 内部 think" 卡片~~——v17 Planner 是普通 ReAct，无引擎编排产生的独立步；~~「任务结果汇总」handoff 子步~~——v17.3 Worker 正文流式下发父步 result，终稿由 complete 兜底，不再追加独立子步 |
| 并行/串行 | 无互相 `dependsOn` 的 worker 行按时间线先后出现；**不画 DAG**，**不按波次分组** |
| handoff | Worker 正文（= handoff）经 content 路由**流式下发到 `worker-*` 父步 result**（与 spawn subagent 正文同构），并双写 H1 `RoundRecord` + Planner L1 尾部；**不再**以「任务结果汇总」子步收束 |
| 不做 | Plan DAG 画布、步骤卡片列表、`{mode}` 标签、把二级 todolist 折叠进 handoff、worker 缩进线、独立"评估进展"卡片、handoff 独立收束子行 |

### 4.2 TaskBoard（一级 / 二级待办 · v17 实时刷新）

复用 `TaskBoardPanel` 软清单体验（D11：禁止 mini-DAG / edges / 工具绑定字段）。

| 层级 | SSOT | 展示规则 | 刷新时机 |
|------|------|----------|----------|
| **一级** | H1 `taskQueue` 投影 | 调度单元 checklist；**单列竖排**（v17 起取消波次分组/横向排列，与 `todo_write` 一致） | **每次状态变更立即刷新**：plan_submit 后、`replaceTaskStatus(in_progress)` 后（worker-start）、worker 完成/失败后（worker-done） |
| **二级** | Worker 内 todolist（`todo_write`，有则挂在对应一级下） | **有 items 才展示**，没有就不渲染该二级区域；Worker 结束**不**把二级板收束进 handoff | Worker 内 todo_write 工具调用时 |

**v17 关键修复**：
- `WorkerDispatchTool` 在 `replaceTaskStatus(in_progress)` 后立即 `emitTaskBoardSnapshot("worker-start")`，**前端 TaskBoard 在 Worker 执行期间显示 → 箭头**（v16 之前只 Worker 完成时 emit，导致箭头看不到）。
- `TaskBoardPanel.vue` 移除 `taskboard-waves` 横向布局（v17 验证后已删），回归 `todo_write` 风格竖排。
- handoff 文案/摘要 **禁止**替代或清空二级 todolist。

### 4.3 步骤契约（正文 · v17 终稿）

| 步骤 | 来源 | 说明 |
|------|------|------|
| `intent` | 路由层 | 同现约 |
| `think` | Planner / Worker ReAct | 轮次间思考；Planner 的 think 与 Worker 的 think 同构展示（一级平铺，Worker 的折进 worker-* 子步） |
| `plan_submit` (元工具) | Planner ReAct | 工具调用步，与普通工具调用同构；中文名"提交调度计划"+ 专属图标 |
| `dispatch_worker` (元工具) | Planner ReAct | 工具调用步；中文名"派发 Worker"+ 专属图标；点击/展开无独立 worker 卡预览（worker-* 由 Worker run 自然产生） |
| `self_assess` (元工具) | Planner ReAct | 工具调用步；中文名"评估进展"+ 专属图标；**可选**——Planner 不调它不代表"未自判" |
| `worker-{id}` | Worker 工具调用 | 时间线一级执行行；v17.5 起以 `WorkerCard` 呈现（复用子 Agent 抽屉）：点击打开 `PlanNodeDrawer`，内层 `subSteps` + 正文 `contentBlocks` + 任务契约（metadata.spawnPrompt）；正文流式不进主时间线穿插 |
| `tasks`（看板） | H1 投影 + 可选 Worker todolist | 与 ReAct `tasks` 步同组件族；harness 下承载一/二级结构；实时刷新 |
| content（综合回答） | Planner ReAct content tokens | **流到主时间线正文**，**不**作为独立步骤卡（v17 取消 `planner-answer` 独立步） |

> **v17 取消**：`plan(R{n})` 独立步骤、`planner-answer` 独立步骤、"评估进展" "提交调度计划" "综合回答 Planner 内部 think" 等引擎补丁步骤。Planner 是普通 ReAct，无须额外步骤形态。**v17.3 进一步取消**「任务结果汇总」handoff 子步--Worker 正文流式下发父步 result，不再以独立子步收束。

### 4.4 前端组件

- **复用**：`OperationStack`（普通时间线骨架）/ `TaskBoardPanel` / `SubStepsFold`
- **扩展**：OperationStack harness 平铺模式（v16）+ Planner 元工具专属图标与中文名（v16）；v17 起 **不再隐藏** `plan_submit`/`self_assess`（旧 v16 隐藏 plan_submit 因 taskBoard 承载，新架构下统一平铺）
- **移除**：`PlanWorkflowPanel` 动态 plan 分支、`PlanApprovalActions` 及 PlanApproval 对 Confirm 壳的绑定；**不新增** Worker 步骤卡片组件；**保留** `CollapsibleConfirmPanel`（HITL/Recovery）；v17 起 TaskBoard 取消 `taskboard-waves` 横向布局
- **与 4.7.9 DecisionCard**：与 PlanApproval 解耦无关——DecisionCard 为 D16 **自建容器**；Planner 注册/续跑见 [D12](./2026-08-12-react-request-decision-planner-d12.md)
- **静态 Workflow 不受影响**：继续用 `PlanExecutionCanvas` 渲染 DAG（D3）

---


## 5. 持久化与故障转移（S2/S3 简化）

### 5.0 PlanNotebook（H1）定稿模型

> 自归档 harness §2.2 迁入并按 S1/S5 清洗：**无** `taskDecomposition` / `Phase` / `completeness` / Evaluator 字段；`TaskItem` **不**嵌套 `PlanJson` DAG。

```java
public class PlanNotebook {
    private final String originalGoal;
    private final String userQuery;
    private String kind;                          // chat | task（会话形态；废字段名 scene）
    private final Deque<TaskItem> taskQueue;      // 可调度粗单元
    private final List<RoundRecord> rounds;
    private double goalCompletion;                // Planner selfAssess
    private String nextDirection;
    private final Instant createdAt;
    private int maxRounds = 12;                   // 默认对齐 §8.1 长负载档（Nacos 可覆盖）
    private int maxTotalTasks = 24;
    private int currentRound;
    private int totalTasksCompleted;
    private int staleRounds;
    private int replanCount;                      // ≤ max-replans（S6；默认 6）
    // 禁止字段：taskDecomposition / phases / currentPhaseIndex / evaluatorReason
}

public record TaskItem(
    String taskId, String label, String status,   // pending|in_progress|done|fail|obsolete
    List<String> dependsOn,
    String constraints, String expectedOutput, String successCriteria) {}

public record RoundRecord(
    int roundIndex, TaskItem task,
    List<NodeResult> nodeResults,
    double roundGoalCompletion, String assessReason) {}

public record NodeResult(String nodeId, String status, String summary) {}
```

- **注入**：`renderForPlanner()` / 注入块 = 当前计划摘要（goal + taskQueue 状态）+ 近 `near-keep-rounds` 轮 rounds 原文；超阈折叠最老轮为摘要（§3.1.1 / §8.1）
- **一级 TaskBoard**：投影 `taskQueue`（§4.2）

### 5.1 PlanNotebookStore（Redis 单写）

`sunshine:plan:notebook:{sessionId}` → PlanNotebook JSON，TTL **7d**（`redis-ttl-seconds=604800`；Chat/Task 统一；对齐 StateStore / [orchestrator-stateless §3.4](../2026-08-03-orchestrator-stateless-design.md)）。**仅 Redis**：

```java
public interface PlanNotebookStore {
    void save(PlanNotebook notebook);              // 覆盖写，每轮结束 save 一次
    Optional<PlanNotebook> load(String sessionId);
    void delete(String sessionId);
    void renewTtl(String sessionId);
}
```

- **不写 MySQL**（冷审计由既有 `PlanExecutionAuditService` → RocketMQ/MySQL/ES 覆盖，S2）
- **无 version 幂等重放**（单写无竞态，S2）
- **无 C1-C4 多级 checkpoint**（每轮结束整体 save 一次 = 原 C4 粒度，S2）

### 5.2 恢复与自愈（复用既有能力）

| 故障 | 恢复 |
|------|------|
| orchestrator 重启 | Redis load PlanNotebook → 未开始/已完成 task 按状态继续；**IN_PROGRESS 一律标记 FAIL → 下轮 Planner 自然 plan_submit 重发**（task 幂等无害，无需查 Worker 死活） |
| Worker 崩溃/超时 | AgentScope 2.0 官方 `StateStore` 自动恢复 Worker 内部 ReAct 循环（TTL 7d）；Planner 层面仅超时等待（`worker.timeout-ms`）→ FAIL → Planner 看到 handoff = 失败摘要后下一轮 think 自然 plan_submit |
| Planner LLM 失败 | Planner run 异常 → `PlannerHarnessLoop.run` 透传错误，store.save 落盘已有结果 → ReactExecutor 降级（`PlannerHarnessExecutor` 兜底） |
| 墙钟熔断 | `PlannerHarnessLoop.applyWallClockGate(maxDurationMs)` 切断 token 流（不抛错），store.save 落盘已有结果 → 前端看到当前已发出的步骤 |
| Redis 不可用 | 内存模式（仅本次 Planner run），run 结束一次性写审计通道 |

> **S3 注记（v3/v4 定稿 · H1 两级压缩）**：Planner 的 run 内压缩由 AgentScope 官方 `CompactionMiddleware`（`HarnessAgent.compaction()`）负责；跨轮 L1 压缩由既有 `L1Compressor` + `far_folded_msg_ids` 负责；**H1 仅注入块（query 前），不建压缩点基建**。H1 注入块**内部两级**（见 §3.1.1）——当前计划摘要（goal + taskQueue 状态）+ 近 N 轮原文（`near-keep-rounds`，默认 **10**，v7 长负载）逐轮追加、超阈值时最老轮次 LLM 折叠为摘要（一次折叠只 miss 尾部小块，C2）；折叠语义与 L1 压缩窗口无关（窗口配置见 §8.1 `notebook.compression`）。**无**阶段骨架 / Phase 协议字段。

### 5.3 降级通道（v17 简化）

```
Planner run 异常（LLM 卡死 / maxDurationMs 触发 / 任何 RuntimeException）
  → PlannerHarnessLoop.run onError 透传 / doFinally store.save 落盘
  → PlannerHarnessExecutor 兜底 fallback_react（终态 degraded_react，partial-context 注入）
Redis 不可用 → 内存模式 → run 结束一次性写审计
```

> **v17 取消**：`Worker TIMEOUT → taskRetryMax → FAIL → Planner replan`（已下放到 Planner 自管，Worker 失败 → handoff = 失败摘要 → Planner 下一轮 think 自然 plan_submit）；`Stale ≥ 阈值 → 强制综合回答`（Planner 不再每波评估，staleRounds 字段保留作审计但不再触发综合）；`任意阶段 maxRounds 耗尽 → Planner 回答`（maxRounds 字段降级审计；Planner 一次性 ReAct 受 max-iters 控制）。
> 
> **对齐旧降级通道**：Planner 全失败降级 React 复用 `fallback_react` 语义（`degraded_react` 终态 + partial-context 注入），保持用户侧降级 UX 一致。

### 5.4 触发式重规划（v17 Planner 自管，S6 简化）

**v17 起**：3 类显式触发从"Loop 检测 → 交 Planner 重规划"改为"**Planner 自然完成 → think 决策 → plan_submit**"。Planner 是 ReAct Agent，自己看 handoff、自己决定下一步；引擎不再机械驱动循环。

| # | 触发 | 检测 | 响应 |
|---|------|------|------|
| ① | **连续失败** | Worker handoff = 失败摘要 → Planner 下一轮 think 看到 | Planner plan_submit 重发（taskId 可同可新；旧 task 标 obsolete） |
| ③ | **目标变更** | 用户 follow-up 更新 `originalGoal` | Planner 下一轮 think 看到新 goal → plan_submit 新 taskQueue（旧的 obsolete） |
| ④ | **进度偏差** | Planner 看到 handoff 后 self_assess `goalCompletion` 不增 → Planner 决定 plan_submit / 收束 | 主动规划调整 |
| — | **预算熔断** | `max-iters=30`（Planner run 内 LLM 调用上限）/ `max-duration-ms`（墙钟） | 落盘已有结果 → fallback_react 兜底 |

**承接但不单列触发**：信息缺口 → Planner 排调研 Worker + handoff 后自然进下一轮 Plan（S5）；资源溢出 → 折叠进预算熔断。

**边界**：
1. **保留成果**：已 `done` 的 task 幂等跳过；plan_submit 时已被新一轮规划撤下的标 `obsolete`（不覆盖已 done）
2. **局部修正**：Planner 改 `taskQueue`，不臆造全局阶段骨架（v17 Planner 根本无阶段骨架字段）
3. **上下文隔离**：重规划读 goal + 已完成 handoff（H1），不读 Worker 内部推理（Worker 内部 ReAct 在 worker-* 子步内，不进 Planner L1）
4. **收敛**：`max-iters=30`（Planner run 内）+ `max-duration-ms`（墙钟）；**不**做 plan-similarity 语义去重
5. **写隔离**：不回滚已完成文件修改（checkout / Git 语义）

---

## 6. 路由接线（对齐 [unified-routing v6](./2026-07-29-unified-routing-design.md)）

> **v6**：用户显式三模式 **快速 `fast` / 专业 `pro` / 工作流 `workflow`**；**取消** L3 自动 `planMode` 识别。专业模式 = 本 spec 的 Planner-Executor；工作流 = 静态 Workflow；动态 Plan-Workflow 删除（D1）。

### 6.1 分发

```
用户选择 executionMode
  ├── fast → ReactExecutor（轨 A：L0–L3 收集 agentIds/skillIds）
  ├── pro  → PlannerHarnessExecutor（轨 A：同上资源包）
  └── workflow → WorkflowExecutor（轨 B：L0–L3 只收集 workflowId；`#` 仅此模式）
```

- **无** `planMode` 字段；**无** `PLAN_WORKFLOW` / `PlanWorkflowExecutor`
- 轨 A **不**收集 workflow；轨 B **不**收集 agent/skill
- 工作流未命中 → 明确失败，**禁止**静默改成 `fast`

### 6.2 golden set 迁移

| 旧用例 | 新语义 |
|--------|--------|
| §A.1 成功路径（PLAN_WORKFLOW） | 用户选 `pro` → PlannerHarnessExecutor |
| §A.2 Replan / 降级 ReAct | harness 校验/Planner 全失败 → 降级 ReactExecutor（终态 `degraded_react`，**不**改用户模式偏好） |
| §A.3 节点重试 | Worker 重试（taskRetryMax） |
| §A.4 关键 tool fail_fast | Worker 失败 → replan → 降级 |
| §A.5 非关键失败 + 残缺 answer | `completed_with_errors` |
| §A.6 fallback_react | `degraded_react` 终态 |
| §A.7 用户确认（Approval） | **删除**（D5） |

---

## 7. 实施阶段（v2 简化后）

### 7.0 落地进度（2026-08-18）

| 阶段 | 状态 | 说明 |
|------|:----:|------|
| **H-0** 基础设施 | ✅ | `PlanNotebook`（字段 `kind`）+ Store 接口 + Nacos `agent.execution.harness` 长负载默认 |
| **H-1** Redis 单写 | ✅ | `PlanNotebookStoreImpl`；键 `sunshine:plan:notebook:{sessionId}` TTL 7d（MySQL Writer 本就未建，S2 无回改债） |
| **H-2** 恢复 | ✅ | load 时 `in_progress`→`fail`；无独立 RecoveryService |
| **H-3** Planner + 校验 | ✅ | `HarnessPlanner`（v17 重写为 `runPlanned`）/ `TaskQueueValidator` / `GoalAlignmentValidator`（保留字段，Loop 不再调用）/ `WORKER`+`forWorker`/`WorkerDispatchTool`/`PlannerActionTool`（`plan_submit`/`self_assess`/`dispatch_worker`，v15 动作工具化）；Catalog `planner.harness`/`harness.worker`；`PlannerAgentRuntime`→ReAct |
| **H-4** Loop | ✅（v17 收敛） | `PlannerHarnessLoop`（v17 仅做墙钟熔断 + 启动 Planner run + `store.save`）；`WorkerContextFactory` |
| **过渡入口**（kernel 附带） | ✅ | 已被 H-5 取代主路径；历史：`harness.enabled`∧`PLAN_WORKFLOW`→harness |
| **H-5** routing v6 三模式 | ✅ | [unified-routing-v6-h5](../../plans/archive/2026-08-13-unified-routing-v6-h5.md)：`fast`/`pro`/`workflow` + ResourceDispatcher；`pro`→harness；冒烟 `verify_routing_v6_smoke.py` |
| **H-6** 前端时间线+TaskBoard | ✅ | [planner-h6-frontend](../../plans/archive/2026-08-13-planner-h6-frontend.md)：分层时间线 + Composer（task 三模式、分支下移、去 AI 提示）；v17 取消 TaskBoard `taskboard-waves` 横向布局回归竖排 |
| **H-7** Live 全量 | ✅ | [planner-h7-live](../../plans/archive/2026-08-13-planner-h7-live.md)：G1–G6 代码 ✅ + `verify_planner_executor_live.py --suite all` **P1–P9 全绿**（含 v17 新架构重跑 P1/P3/P4；P5 重启恢复通过；P8 长墙钟默认 skip） |
| **阶段 D** 删旧 plan-workflow | ✅ | = routing **R-4**；`WorkflowPlanner`/`PlanWorkflowExecutor`/`PlanApproval*`/`PLAN_WORKFLOW` 源码零残留；`PlanMaterializer`/`PlanNormalizer`/`PlanTimeline`/`PendingInteraction`/`ResumeInteractionHint` 经核对为**静态 Workflow/HITL/Recovery 复用**改列保留（§2.1）；全量 Live 回归随 H-7 部署后跑 |
| **H-8** 一次性 ReAct run（v17） | ✅ | **本版**：Planner 从 Loop 驱动拆 3 次独立 run 改为**一次性 ReAct run**（`HarnessPlanner.runPlanned`）；`PlannerHarnessLoop` 收敛为 ~70 行（墙钟熔断 + 启动 + store.save）；Worker dispatch 立即 emit in_progress TaskBoard 快照；前端不再隐藏 plan_submit/self_assess 工具步；`AgentExecutionProperties.Harness.Planner.maxIters=30` 新增 + Nacos SSOT；`HarnessPlannerTest` / `PlannerHarnessLoopTest` 按新 API 重写；44/44 harness 单测绿 |

**代码落点**：`orchestrator/.../plan/harness/*` · 灰度 `docs/nacos/sunshine-orchestrator.yaml` → `agent.execution.harness.enabled` · 冒烟 `scripts/verify_planner_harness_kernel_smoke.py`。

### 阶段 H-0：基础设施 ✅

- [x] `PlanNotebook` POJO（goal + taskQueue/`TaskItem` + `RoundRecord`/`NodeResult`；**无** `taskDecomposition` / `Phase` / `completeness`；会话形态字段 **`kind`**）
- [x] `PlanNotebookStore` 接口（Redis 单写四方法）
- [x] Nacos / `AgentExecutionProperties.Harness` 长负载默认（§8.1）
- **出口**：单测绿

### 阶段 H-1：持久化实现 ✅

- [x] `PlanNotebookStoreImpl`（Redis 单写，每轮 save / renewTtl）
- [x] **回改**：无存量 MySQL Writer 可删（S2 从一开始即 Redis-only）
- **出口**：单测（save→load 一致性）

### 阶段 H-2：恢复 ✅

- [x] 恢复 = Redis load + IN_PROGRESS→FAIL→replan 规则（S2），与 Store 单测合并
- **出口**：单测（Redis load + 状态修复）

### 阶段 H-3：HarnessPlanner + 校验 ✅

- [x] `HarnessPlanner`（planNext / selfAssess / synthesizeAnswer；**无**模式自判）
- [x] `PlannerActionTool`（v15 动作工具化）：`plan_submit` 覆盖 taskQueue、`self_assess` 写决策；`DispatchSession.ActionSignals` 判定动作收到；不解析正文 JSON
- [x] `GoalAlignmentValidator`（DEVIATED/STUCK，机械薄实现；非完整 4.7.7 Middleware）
- [x] `TaskQueueValidator`（S7，不复用 PlanValidator）
- [x] `AgentRole.WORKER` + `AgentRunRequest.worker()` + `AssembledContext.forWorker()` + `WorkerDispatchTool`
- [x] Catalog 种子 `planner.harness` / `harness.worker`
- **出口**：单测（调度单元输出 + forWorker 上下文构造）

### 阶段 H-4：Loop ✅

- [x] `PlannerHarnessLoop`（Plan→Validate→Execute→Assess；预算 / `nextDirection` / Stuck）
- [x] `WorkerContextFactory`（从 H1 rounds 读上游 handoff，S4）
- **出口**：单测（循环编排 + 故障 / maxReplans 模拟）

### 过渡入口（kernel 附带，非完整 H-5）✅

- [x] `PlannerHarnessExecutor`（load/create notebook → `loop.run` → renewTtl；`fallbackReact`）
- [x] `ExecutionDispatcher`：`harness.enabled` ∧ `PLAN_WORKFLOW` → harness，否则旧 PlanWorkflow
- [x] 内核冒烟脚本（非 §9.2 全量）
- **非本阶段**：`executionMode=pro` / 删旧入口 → 见 H-5

### 阶段 H-5：路由接线 ✅

- [x] `PlannerHarnessExecutor` + `ResourceDispatcher`：`executionMode=pro` → harness（[routing v6](./2026-07-29-unified-routing-design.md)；plan [unified-routing-v6-h5](../../plans/archive/2026-08-13-unified-routing-v6-h5.md)）
- [x] 三模式显式选择替代 `auto`/`planMode`；主路径不再调用 `PlanWorkflowExecutor`
- [x] 冒烟：`scripts/verify_routing_v6_smoke.py`（V1/V3/V4/V5）
- **源码删除**（`PlanWorkflow*` 等）→ **R-4 / 阶段 D**（已完成）；`ForcedExecutionRouter` **重写语义保留**，本阶段不断流即可
- **出口**：`pro` 进 harness、`fast`/`workflow` 不进；编译绿 ✅

### 阶段 H-6：前端（分层时间线 + TaskBoard）✅

> 实施计划：[planner-h6-frontend](../../plans/archive/2026-08-13-planner-h6-frontend.md) ✅（`f9d9a6e1`…`6de8b35d`）

- [x] OperationStack harness 层级：plan → worker 行 → subSteps（普通时间线，非卡片）；~~+ **handoff 行**~~（v17.3 去除，Worker 正文流式下发父步 result，不再以独立子步收束）；**v17.4 展开区正文去重**：`OperationCard` 对 worker 步跳过 op-detail，正文唯一由嵌套 `OperationStack` 的 contentBlocks 承载（result 不再渲染，避免双份）；**v17.5 Worker 抽屉化**：`WorkerCard` 点击打开 `PlanNodeDrawer`（worker 播放环图标 + 任务契约 + contentBlocks + subSteps），正文不再主时间线穿插
- [x] TaskBoard Panel API / 二级 todolist；**一级 H1 完整投影**仍依赖 harness `tasks` SSE（follow-up，不阻塞时间线）
- [x] 断开 `PlanApprovalActions` / PlanApproval→Confirm 绑定（**保留** `CollapsibleConfirmPanel` 供 HITL/Recovery，D5；组件文件阶段 D 已删）
- [x] 静态 Workflow 保留 DAG 展示（D3）；无 `planGraph` 的 harness 走普通时间线
- [x] **Composer UX**：`kind=task` 亦可选 `fast|pro|workflow`；`GitBranchSelector` 在输入框下方；去掉「AI 生成内容仅供参考」提示
- **出口**：视觉验收 ✅

### 阶段 H-7：Live 验收 🟡

> 实施计划：[planner-h7-live](../../plans/archive/2026-08-13-planner-h7-live.md)（代码 ✅）

- [x] **验收前置**：H1→`tasks` SSE；worker 步 handoff 文案；`planner-answer` 步；follow-up 目标变更→`obsolete`；薄审计 `plan.worker_*`
- [x] `scripts/verify_planner_executor_live.py`（§9.2 P1–P8；`--suite p1,p3,p4` 最短三角）
- [ ] **全量 Live 绿**：部署 `feature/planner-h7-live` → `python scripts/start.py --restart orchestrator` → 跑脚本
- [ ] **v17 重跑 P1/P3/P4**：H-8 架构重构后需重跑验证——重点关注时间线平铺（无独立"评估进展"卡片）、TaskBoard in_progress → 箭头、Planner 元工具步平铺展示
- 回归：静态 Workflow、ReAct、spawn
- **已有**：`verify_planner_harness_kernel_smoke.py`（不替代本阶段）

### 阶段 H-8：Planner 一次性 ReAct run（v17）✅

> **根因**（v15/v16 实操暴露）：「时间线平铺」实操中反复打补丁（评估进展重复 / 自判脱节 / TaskBoard in_progress 缺失），根因在 v15 把 Planner 的规划 / 派发 / 自判拆为 Loop 引擎驱动的 3 次独立 ReAct run。CLAUDE.md「**两三轮仍不能解决 → 停补丁，查本质**」+ 用户「**从架构方面重构时间线**」触发本版。

- [x] **`HarnessPlanner.runPlanned()`** 取代 `planNext + selfAssess + synthesizeAnswer`——Planner 一次性 ReAct run（AgentScope `AgentRuntime.run(AgentRole.PLANNER)`），`max-iters=30` 兜底
- [x] **`PlannerActionTool` 三个元工具齐备**：`plan_submit` / `dispatch_worker` / `self_assess`，全部由 Planner 在自己的 ReAct run 内调用；`DispatchSession.ActionSignals` 保留作审计字段，v17 不再驱动循环
- [x] **`PlannerHarnessLoop` 收敛为 ~70 行**——`planner.runPlanned + applyWallClockGate(maxDurationMs) + doFinally store.save`；删除 `wave` / `executeTaskWithRetries` / `resolveAssessDecision` / `hasNoOutstandingWork` / GoalAlignmentValidator 调用 / 兜底 Worker 调度
- [x] **`WorkerDispatchTool` 立即 emit TaskBoard in_progress 快照**——`replaceTaskStatus(in_progress)` 后立即 `emitTaskBoardSnapshot("worker-start")`，前端 TaskBoard 显示 → 箭头
- [x] **`AgentExecutionProperties.Harness.Planner` 新增 `maxIters=30`**——Planner 一次性 ReAct 轮数上限
- [x] **Nacos SSOT**：`docs/nacos/sunshine-orchestrator.yaml` `agent.execution.harness.planner.max-iters: 30` 已加
- [x] **前端 `OperationStack.vue` 不再隐藏 plan_submit/self_assess**——v16 隐藏 plan_submit 因 taskBoard 承载，新架构下统一平铺
- [x] **`HarnessPlannerTest` / `PlannerHarnessLoopTest` 按新 API 重写**（删除旧 `planNext / selfAssess / synthesizeAnswer` 用例，新增 `runPlanned` / `run` 用例）
- [x] **44/44 harness 单测通过**
- [x] **orchestrator + sunshine-ui 已重启**（:8200 UP / :5173 UP）
- [ ] **P1 Live 验证**——跑 `python scripts/verify_planner_executor_live.py --suite p1`（用户接受前）
- **未引入**：Phase 5 5.x 模型分层（Planner LLM 强弱分层）、Checkpoint 续跑（v17 Planner run 一次到位，不需要）

### 阶段 D（删除）：旧 plan-workflow 代码清理 ✅

- [x] 删除 `WorkflowPlanner` / `PlanWorkflowExecutor` / `PlanApprovalService` 全套（`PlanApprovalUserAction` / `PlanApprovalDecision` / `PlanApprovalRound` / `PlanApprovalWaitResult` / `PlanApprovalRejectedException`）、`ExecutionMode.PLAN_WORKFLOW` 与路由入口
- [x] **核对改列保留**：`PlanMaterializer` / `PlanNormalizer` / `PlanTimeline` / `PendingInteraction` / `ResumeInteractionHint` 经代码核对为**静态 Workflow / HITL / Recovery 复用**，移入 §2.1 保留表，**不删**
- [x] 清理 Catalog `planner.prompt` / `plan-workflow.*`、Nacos `agent.execution.plan-workflow`（grep 零残留）
- [x] 前端删 `/plans/:planId` 动态 plan 专属部分、`PlanApprovalActions` / `PlanWorkflowPanel`（源码零残留；仅测试文件历史文案残留）
- **出口**：grep 零残留 ✅；全量 Live 回归随 H-7 部署后跑

> **实施顺序**：H-0→H-6 ✅ → H-7（代码 ✅，Live 待部署）→ 阶段 D（R-4）✅。阶段 D 是纯减法，不阻塞 harness 灰度；H-7 Live 部署后一并跑全量回归。

---

## 8. 组件与配置

### 8.1 Nacos 新增

> **v7 长负载档（2026-08-13）**：面向多 Worker、可 spawn、含沙箱/工具墙钟的专业模式长任务。对齐现网 `agent.execution.react`：`task-max-iters=100`、`async-tool.spawn-await` 上限约 600s、`exec-wall-timeout-sec=600`、`subagent.timeout-ms=180000`。旧稿 10min 总墙钟 / 2min Worker **过短**，会在真实 coding/调研波次中误熔断。数值为**经验初值**，Live（P1/P2/P7）后可调。

```yaml
agent:
  execution:
    harness:
      enabled: false              # 灰度开关；kernel 冒烟可临时 true（见 §7.0）
      # —— 循环预算（长负载）——
      max-rounds: 12              # v7 保留（降级审计字段；v17 Planner 一次性 run 不再驱动 wave）
      max-total-tasks: 24         # v7 保留（降级审计字段；v17 Planner 一次性 run 不再驱动 wave）
      max-duration-ms: 14400000   # PlannerHarnessLoop 墙钟熔断（v17 主用）
      stale-rounds-threshold: 3   # v7 保留（v17 Planner 不再每波评估，staleRounds 字段保留但不再触发综合）
      task:
        max-retries: 2            # 单 task 失败重试（Worker 内部语义）
      planner:
        timeout-ms: 300000        # Planner 一次性 ReAct run 整体超时（fallback 兜底）
        max-attempts: 3           # 保留兼容；v17 Planner run 异常由 PlannerHarnessExecutor 降级 React
        max-replans: 6            # v7 保留（v17 Planner 自然重规划，不再依赖此字段，但保留 Nacos 兼容）
        # v17 新增：Planner 一次性 ReAct run 轮数上限（30 轮 ≈ 30 次 think+工具对）
        max-iters: 30
      worker:
        # 须覆盖：Worker 内 ReAct 多轮 + spawn 观察窗（≤~600s）+ sandbox exec-wall（600s）
        timeout-ms: 3600000       # 单 Worker 墙钟 1h
        max-sub-agents: 5         # 可拆并行子任务
        # 建议 Worker 内 maxIters 默认取 react.task-max-iters（100），chat 场景可降为 react.max-iters
      notebook:
        redis-ttl-seconds: 604800 # 7d；与 §5.1 / StateStore / sandbox purge 一致
        key-prefix: "sunshine:plan:notebook:"   # 完整键 = prefix + sessionId；对齐 orchestrator-stateless
        compression:              # H1 注入块内部两级（§3.1.1）：
          near-keep-rounds: 10    # 近 N 轮原文；超阈最老轮 LLM 折摘要
                                  # 与 L1 压缩窗口（五层 v14/v15）无关，勿混用
      # 无独立 recovery.*（S2：恢复 = Redis load + IN_PROGRESS→FAIL→replan）
      session:
        idle-timeout-ms: 14400000 # 空闲续跑窗口对齐 max-duration
```

| 参数 | v6 旧值 | v7 长负载 | v17 调整 | 对齐依据 |
|------|---------|-----------|----------|----------|
| `max-rounds` | 5 | **12** | 保留字段（降级审计） | v17 Planner 不再驱动 wave；字段保留以备回潮或审计 |
| `max-total-tasks` | 10 | **24** | 保留字段（降级审计） | 同上 |
| `max-duration-ms` | 600000 (10m) | **14400000 (4h)** | **PlannerHarnessLoop 主用**（v17） | PlannerHarnessLoop 墙钟熔断 |
| `stale-rounds-threshold` | 2 | **3** | 保留字段（不再触发综合） | v17 Planner 不再每波评估 |
| `task.max-retries` | 1 | **2** | 保留（Worker 内部） | 工具抖动后再重试 |
| `planner.timeout-ms` | 60000 | **300000** | Planner run 整体超时 | ReAct Planner + 综合回答 |
| `planner.max-attempts` | 2 | **3** | 保留兼容 | Planner run 异常降级 React |
| `planner.max-replans` | 3 | **6** | 保留字段（不再驱动循环） | v17 Planner 自然重规划 |
| **`planner.max-iters`** | — | — | **新增 = 30（v17）** | Planner 一次性 ReAct run 轮数上限 |
| `worker.timeout-ms` | 120000 | **3600000** | 同 v7 | ≥ spawn 窗 + exec-wall + 多轮工具 |
| `worker.max-sub-agents` | 3 | **5** | 同 v7 | 可拆并行子任务 |
| `notebook.redis-ttl-seconds` | 86400 | **604800** | 同 v7 | 与 §5.1「7d」一致 |
| `near-keep-rounds` | 6 | **10** | 同 v7 | 长会话 H1 近轮记忆 |
| `session.idle-timeout-ms` | 1800000 | **14400000** | 同 v7 | 对齐总墙钟 |
| `recovery.*` | 有 | **删除** | 同 v7 | S2 无独立 RecoveryService |
| `react.async-tool.max-concurrent-per-message` | 3 | — | **10（v17.8）** | 同消息并发派发上限（worker/spawn/exec 共享槽位），满足 pro 多路并发调研 |
| `react.async-tool.worker-await-default-sec` | — | — | **120（v17.8）** | Worker dispatch 单次 await 默认观察窗口 |
| `react.async-tool.worker-await-max-sec` | — | — | **600（v17.8）** | Worker dispatch 单次 await 上限（对齐 task 墙钟 1h） |
| `react.async-tool.worker-await-max-waits` | — | — | **6（v17.8）** | Worker dispatch 等待预算（6×600s 观测窗口，允许长 Worker） |

> **S2/S5 裁撤配置**：无 `checkpoint.mysql-*` / `version-gap-alert`（Redis 单写）；无 `evaluator.*`（S1）；无 `plan-similarity-threshold`（S6）；无 `recovery.*`（S2）。  
> **与 react 配置关系**：Worker/子 Agent 的 **iters** 不在 harness 重复发明第二套上限，默认复用 `react.task-max-iters` / `react.subagent.max-iters`；harness 只管 **墙钟与波次预算**。
> **v17 新增**：`planner.max-iters=30`——Planner 一次性 ReAct 的轮数上限（Nacos SSOT `agent.execution.harness.planner.max-iters`）。

### 8.2 Catalog 新增

| ID | 用途 |
|----|------|
| `planner.harness` | Planner system prompt（v17：**单套 `PLANNER_HINT` 取代三阶段 HINT**——动作经 `plan_submit` / `dispatch_worker` / `self_assess` / `task_status` 元工具表达，正文禁止输出 JSON/伪代码；Planner 停止调工具直接输出 content = 综合回答；按现有信息排调度单元；信息不足先调研；自判可选；**禁止** full/hier 模式标签、计划 JSON、伪通道标签。**v3（v17.8）**：工具列表修正为 `think_summary`/`plan_submit`/`dispatch_worker`/`await_tool_run`/`task_status`/`self_assess` 六件套，明确无 `wait_async_results`/`task_list`/`task_output`；并发上限 10；failReason 分类与重派语义（t1-2/t1-3 最多 3 次执行）） |
| `harness.worker` | Worker system prompt（forWorker 模板；单元内细则展开） |

> **S1/S5 裁撤**：`harness.task-evaluator` / `harness.goal-evaluator` / `planner.phase` **不建**（统一 Planner 自管；调用点 `callSite=plan`）。
> **v17 取消**：`HINT_PLAN` / `HINT_ASSESS` / `HINT_ANSWER` 三套阶段 hint 合并为 `PLANNER_HINT`（v15 的引擎阶段协议不再需要，Planner 在同一 ReAct run 内自然决策）。

### 8.3 Catalog 废弃

- `planner.prompt`（一次性规划）→ 由 `planner.harness` 取代
- `plan-workflow.user-modification` / `plan-workflow.replan-feedback` / `plan-workflow.upstream-failure-line` → 删除

---

## 9. 验收标准

### 9.1 单测（v17 修订）

| 用例 | 预期 |
|------|------|
| **一次性 ReAct run（v17）** | `HarnessPlanner.runPlanned` 返回 `Flux<StreamToken>`；Planner run 内调 `plan_submit` → taskQueue 写入；session 在 run 终止时清理；content tokens 流到主时间线 |
| **Loop 仅做熔断（v17）** | `PlannerHarnessLoop.run` 仅启动 Planner run + 墙钟熔断 + store.save；异常透传；无 wave / `executeTaskWithRetries` / `resolveAssessDecision` 调用 |
| **任务工具态独立（v15）** | `plan_submit` / `self_assess` / `dispatch_worker` 三个 AgentTool 独立注册到 Planner toolkit；Planner 不会调用 sandbox/rag 等业务工具 |
| Worker 执行行折叠（v15） | Planner 直接调度时 `WorkerTimelineBridge` 发 `worker-{taskId}` 一级行，内部 think/tool 折进 `subSteps`；v17 起 fold 始终生效（Planner session 持续到 run 结束）；begin→running→complete/done（或 error）生命周期完整 |
| **Worker 正文流式（v17.3）** | `WorkerDispatchTool.foldStepToken` 对 content 三类 token（`isContent/isContentStart/isContentEnd`）也进 `bridge.wrap`，经 `SubAgentContentTokens.route` 流式下发父步 result；`WorkerTimelineBridge.complete` 终稿兜底覆盖，**不再**追加「任务结果汇总」handoff 子步 |
| **Worker 展开区正文去重（v17.4）** | Worker 正文同时存在于父步 `contentBlocks`（分段路由）与 `result`（终稿兜底），前端 `OperationCard` 展开区与嵌套 `OperationStack` 双渲染。`resolveStepExpandInner` / `resolveStepExpandLead` 对 worker 步返回空，`OperationCard` op-detail 跳过，正文唯一由嵌套 stack 的 contentBlocks 承载；单测 `processingStepsDisplay.worker.test.ts` |
| **Worker 抽屉化（v17.5）** | Worker 卡迁移子 Agent 抽屉：`WorkerCard`（worker 播放环图标）点击打开 `PlanNodeDrawer`；父步 metadata 携带任务契约（`buildStablePrefix` 经 `spawnPrompt`）；抽屉展示「任务契约」+ contentBlocks 穿插 + subSteps 时间线（复用 agent 分支）；`findAgentNodeStep` 支持 `worker-*`；正文不再主时间线穿插（并发多 Worker 各自抽屉独立展示） |
| **Worker 并发流式 + 重试链（v17.7）** | `dispatch_worker` 强制 `background=true`，`Flux.subscribe()` 不阻塞；Worker run 返回 runId；失败/取消后 `TaskItem` 版本化（`baseTaskId`/`retryIndex`/`parentTaskId`/`failReason`）；同 baseTask 第 4 次派发**被拒绝**（重试上限 2 次）；`failReason` ∈ {`timeout`, `error`, `cancelled`}；TaskBoard 失败/取消行显示 ⊗ 不清除 |
| **Worker 并发流式 + 状态机（v17.7）** | `dispatch_worker` 强制 `background=true`（`Flux.subscribe()` fire-and-forget，立即返回 runId）；Worker token 并发流式（多 Worker 抽屉同时推进）；TaskItem 版本化（`baseTaskId`/`retryIndex`/`parentTaskId`/`failReason`）；同 baseTask 最多 3 次执行（重试 2 次），超出拒绝派发；失败/取消不删历史，TaskBoard ⊗ 标记 |
| **await_tool_run 批量等待（v17.9）** | `AwaitToolRunTool` 新增 `runIds` 数组参数，`AsyncToolRunRegistry.awaitMany` 并行等待同轮多 run（共享观察窗口，返回 `runs[]`；未终态含 running 快照）；单值 `runId` 兼容；`planner.harness` v4 提示词引导批量收集，禁止逐任务串行等 |
| **Planner 注册 await_tool_run（v17.8）** | `buildForPlanner` 显式注册 `await_tool_run`（dispatch 强制异步的收集通道）；`ReActAgentRuntime` 将 PLANNER 注册 main run；`AwaitToolRunTool` 对 planner-* bridge 放行 |
| **task_status 工具（v17.8）** | `PlannerActionTool` 注册 `task_status`；`submitTaskStatus` 输出全量任务元数据（taskId/status/retryIndex/failReason/dependsOn），保留历史版本顺序；无 session 返回错误 |
| **Worker await 参数（v17.8）** | `AsyncToolRunRegistry` 对 WORKER_DISPATCH 用 `worker-await-*`（默认 120s / 上限 600s / 6 次等待）；超预算 `BUDGET_EXHAUSTED` |
| **并发上限 10（v17.8）** | `maxConcurrentPerMessage=10`；槽位测试显式固定 3 验证语义 |
| **取消终止流式（v17.8）** | 用户取消 Worker 后 `RUN_SUBSCRIPTIONS` dispose 订阅；fold guard 阻止取消后继续折叠正文（`userCancel_stopsSubsequentStreamingFold`）；TaskItem 终态 `cancelled` |
| **TaskBoard in_progress 即时刷新（v17）** | `WorkerDispatchTool.dispatchWorker` 在 `replaceTaskStatus("in_progress")` 后立即 `emitTaskBoardSnapshot("worker-start")`；前端 TaskBoard 在 Worker 执行期间显示 → 箭头 |
| 单一循环无模式字段 | PlanNotebook / plan 步 **无** `taskDecomposition` / full|hier；引擎无模式分支 |
| 信息不足→调研→重规划 | Planner 一次性 ReAct 内首轮可只排调研单元；dispatch_worker 同步等 handoff；handoff 后 Planner 下一轮 think 自然 plan_submit |
| 职责边界 | Planner 调度单元粗粒度；Worker handoff 可含执行结果摘要（作为 tool_result 进 Planner L1）；二级 todolist 不收束进 handoff |
| TaskBoard 条件展示 | 无二级 items 时不渲染二级区；有则原样展示至会话可见 |
| Worker handoff 双写 | L1 尾部 append + H1 更新 |
| Worker 上下文隔离 | forWorker 含 taskGoal+constraints（v3：**不注入 L2**）；toolWhitelist 经 `AgentRunRequest` 运行时控制注册、不写入 prompt；内部 think/tool 不回流 |
| H1 两级折叠（v3） | 注入块近 `near-keep-rounds` 轮原文，超阈值最老轮次 LLM 折叠为摘要 |
| Planner L1 组装一致性（v3） | Planner 复用 `ContextAssembler.assemble`（chat 含 L3），与普通 ReAct MAIN 差异仅 H1 注入块 + worker handoff |
| Planner 元工具中文名+图标（v16） | `plan_submit` / `self_assess` / `dispatch_worker` 在 `ToolCatalogService` 内建表 + 专属图标 |
| Planner run 异常降级 | `PlannerHarnessLoop.run` 异常时 store.save 落盘；ReactExecutor 降级 |
| **取消的旧引擎逻辑（v17）** | `resolveAssessDecision` / `hasNoOutstandingWork` / `executeTaskWithRetries` / `plannerNext` / `synthesizeAnswer` 等方法 **不存在**（grep 零残留） |

### 9.2 Live（v17 重跑 P1/P3/P4）

| # | 场景 | kind | 预期 | v17 重点关注 |
|---|------|:---:|------|--------------|
| P1 | 分析 Q2 销售下降 + 改进方案 + 预算 | chat | Planner→Worker→自判→综合；分层普通时间线 + 一级看板；handoff 仅在时间线 | **时间线无独立"评估进展 / 提交调度计划"卡片**；Planner 元工具步平铺（中文名 + 图标）；TaskBoard 在 Worker 执行期间显示 → 箭头；**v17.7 多 Worker 并发流式**（多抽屉同时推进 token，非缓冲） |
| P2 | 修复 SQL 注入风险 + 单测 | task | Planner→Worker(内部 spawn)→自判→综合 | 同上 |
| P3 | 静态 Workflow 回归 | / | `#knowledge-qa` DAG 展示正常（D3 保留） | harness 路径未受影响 |
| P4 | 简单问答回归 | / | 走 ReactExecutor | 不进 harness |
| P5 | 崩溃恢复 | chat | Kill orchestrator → 重启 → 恢复 Notebook → 继续 | Planner run 异常落盘已有结果 |
| P6 | 长任务上下文压缩 | chat | 超 `near-keep-rounds`（默认 10）后 H1 最老轮折摘要；配合 AgentScope Compaction + L1Compressor | Planner 一次性 run 内仍受 AgentScope 官方 Compaction 控制 |
| P8 | 长负载预算不误熔断 | task | 单 Worker 含 spawn+沙箱 exec 墙钟接近 600s 时**不**因 `worker.timeout-ms` 误杀；整次 run 在 4h 内可完成多波次 | PlannerHarnessLoop `applyWallClockGate(maxDurationMs=14400000)` |
| P7 | 信息不足先调研再重规划 | task | Planner 首轮排调研 Worker；handoff 后下一轮 think 自然 plan_submit；Worker 有 todolist 则二级板展示，结束板不收束 | **Planner 在同一 ReAct run 内连续 think → plan_submit**（v17 根除 wave 切换） |
| P9 | Worker 失败重试链（v17.7 新增） | task | Worker 超时/异常/取消后，Planner 重派同任务生成 t1-2/t1-3；TaskBoard 保留历史失败行 ⊗；第 3 次仍失败后 Planner 收束或改派新任务 t2-1 | 并发流式不阻塞；`failReason` 分类正确；重试上限代码层硬控制 |

---

## 10. 风险与对策

| 风险 | 对策 |
|------|------|
| 删除旧 plan-workflow 影响静态 Workflow | D3 明确 DAG 画布保留服务静态 Workflow；`WorkflowExecutor` 独立于 `PlanWorkflowExecutor` |
| harness 灰度期无路由入口 | `agent.execution.harness.enabled` 开关 + 直接 API 接入，不依赖 L3 路由即可验证 |
| `AgentRole.WORKER` 破坏现有角色逻辑 | 新增枚举值不改现有 MAIN/SUB/PLANNER 行为；`resolveBridgeId` 加 WORKER 分支 |
| `AssembledContext.forWorker()` 上下文不足 | 稳定前缀（taskGoal + 共享快照 + handoff）+ query；工具白名单经 `AgentRunRequest.toolWhitelist` 运行时注册 |
| 终态/审计分叉 | D8：复用 `ExecutionPlanStatus` + `PlanExecutionAuditService` |
| 前端两套时间线并存 | harness 走 OperationStack 分层普通时间线 + TaskBoard；静态 Workflow 走 PlanWorkflowPanel DAG；看板与 handoff 职责分离（§4 v5） |
| **v17 Planner run 不能跨 turn（v17 新增）** | 一次性 ReAct run 受 `max-iters=30` 兜底；墙钟熔断（`max-duration-ms`）兜底；如需跨 turn 续跑，引入 Redis StateStore checkpoint（v17 不实现） |
| **v17 Planner 提示词变长（v17 新增）** | 单套 `PLANNER_HINT` 描述完整动作协议（plan_submit / dispatch_worker / self_assess + 收束协议）；Catalog 训练 LLM 按协议执行；P1 Live 验证 LLM 行为 |
| **v17 Planner 与 Worker run 间无 Loop 监督（v17 新增）** | Planner 自带反馈闭环（handoff 进 L1 历史）；task 内部重试仍由 `task.max-retries` 保留；校验由 `plan_submit` 工具内部校验 taskQueue 结构 |
| **v17 调试链路变深（v17 新增）** | PlanNotebook 仍记录 taskQueue / goalCompletion / replanCount，可审计；ReAct trace 通过现有 ReActAgentRuntime 日志（推理 + 工具调用）输出 |
| **v17 Planner 自然重规划（v17 新增）** | 取消 Loop 机械 `resolveAssessDecision`；Planner 自决何时 plan_submit / 收束；`max-iters=30` + `max-duration-ms` 兜底 |

---

## 11. 关联文档

| 文档 | 关系 |
|------|------|
| [archive/planner-harness-loop v8](./2026-07-31-planner-harness-loop-design.md) | **已归档废案**（三态分解 / Evaluator / MySQL 双写 / H1 压缩点等）；定稿模型见本文 §5.0 / §5.4，勿再改归档稿 |
| [unified-routing-design v6](./2026-07-29-unified-routing-design.md) | 用户三模式 fast/pro/workflow；轨 A/B 意图收集；`pro`→本 Executor；**命名四轴**（`kind` / `executionMode` / `biz_scene` / `callSite`） |
| [orchestrator-stateless](../2026-08-03-orchestrator-stateless-design.md) | Redis 键 `sunshine:plan:notebook:{sessionId}`；Activity 化波次 B2/B3 后置 |
| [react-goal-alignment](./2026-07-27-react-goal-alignment-design.md) | S6④ Validator **前置**（未落地可降级） |
| [specs/README 依赖顺序](../README.md#活跃增量方案依赖与落地顺序2026-08-13) | 跨 spec 落地顺序 SSOT |
| [expert-consultation-design](./2026-07-07-expert-consultation-design.md) | peer-collab（已退役），spawn 中心化替代 |
| [plan-user-approval-design](./2026-06-27-plan-user-approval-design.md) | **被 D5 废弃** |
