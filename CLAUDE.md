# CLAUDE.md

Sunshine AI Platform — 企业级 AI 中台（AgentScope-Java + Spring Cloud Alibaba + Vue3/Naive UI）。

## 编码要义（最高优先级）

1. **两三轮仍不能解决 → 停补丁，查本质**：同一 bug 改 2–3 轮仍反复或出新症状，必须质疑**架构**与 **Catalog 提示词**的合理性；禁止继续打补丁式修改。
2. **找根因，简化设计**：优先从链路建模、SSE/步骤契约、提示词入手修正；方案要**简单**，禁止冗余分支与「兼容旧行为」的兜底逻辑。
3. **模型输出不二次加工**：禁止对模型输出做截断、摘要或过滤兜底；不对就改 Catalog/`/prompts` 或架构。

**进度**：阶段三 ✅ — 阶段四 **4.6 动态 DAG ✅** · **4.7 多智能体协作 ✅** · **4.7.5 ReAct TaskBoard ✅** · **4.7.6 Spawn Subagent ✅** · **4.7.9 Request Decision ✅**（Chat MAIN；Cursor 对齐；**Planner D12 ✅**——Planner MAIN 注册 `request_decision`（`decision.enabled`）+ 暂停/续跑同契约）· **异步工具 await ✅**（`background` + `await_tool_run`；exec/spawn；Live `verify_async_tool_await_live.py`）· **4.8 工具集成 ✅** · **4.13 Workflow Studio ✅** · **4.5 沙箱方案 B ✅** · **4.5 Codex 工作区 ✅** · **4.11 Prompt Catalog ✅** · **4.13.8 结构化 I/O ✅** · **4.14 Planner-Executor 重建 ✅**（H-0～H-8 ✅；**v17 一次性 ReAct run ✅**——Planner = 普通 ReAct + 动作元工具，`PlannerHarnessLoop` 收敛为熔断+启动；Worker 独立 sessionId 并行流式 + 版本化重试（t1-1/t1-2/t1-3）+ `await_tool_run` 批量收集 + taskQueue 独立下发；前端 Worker 迁移子 Agent 抽屉、运行态阶段正文/「正在收尾回复」、T1-1 记号统一、卡片按任务序号稳定排序；Live P1–P9 全绿 ✅；**阶段 D ✅**——`PlanWorkflowExecutor`/`WorkflowPlanner`/`PlanApproval` 源码与读侧兼容已删；v17.1–v17.20 逐版变更明细见 [rebuild spec 变更记录](docs/superpowers/specs/2026-08-05-planner-executor-rebuild-design.md)）· **统一路由 v6 ✅**（R-0～R-4：wire 仅 fast/pro/workflow，读侧兼容已去除，请求字段名统一 `executionMode`；[spec](docs/superpowers/specs/2026-07-29-unified-routing-design.md)·[plan](docs/superpowers/plans/2026-08-13-unified-routing-v6-h5.md)）· **Kind·Biz-Scene Catalog ✅**（资源 `kind` 过滤 · 业务场景 Lab · 工具集 chat/task · 退役 react-prompt；[spec](docs/superpowers/specs/archive/2026-08-13-kind-biz-scene-catalog-design.md)）· **模型注册表 ✅**（MySQL SSOT + `/models` + scene 绑定；[spec](docs/superpowers/specs/archive/2026-07-27-model-registry-config-design.md)）· **服务合并 ✅**（skill/agent/prompt/desensitize → resource-manager :8240 · oa/finance/hr → biz-simulator :8700 · tool-manager 更名 tool-service :8210；[spec](docs/superpowers/specs/archive/2026-08-03-service-consolidation-design.md)）· **时间线前缀图标 ✅**（极简/标准双模式；行首类型图标 + chevron 迁移；[spec](docs/superpowers/specs/archive/2026-08-14-timeline-style-prefix-icon-design.md)）· **Usage 状态栏 ✅**（轮次/输入输出/ctx 分组；Live `verify_usage_stream_live.py`；[spec](docs/superpowers/specs/2026-08-17-react-usage-context-display-design.md)）· **任务清单记忆 ✅**（**M0** fast 跨轮恢复——`react_task_board` 终态快照注入【任务清单】块，Live `verify_task_list_restore_live.py` T1–T4；**M1** KV Memory 统一 + `todo` 类——`user_context_state` 加 `scope` 列、`context.memory.extract` 参数化、v22 门禁、完成即 void、chat/task scope 隔离，Live `verify_kv_memory_todo_live.py` T1–T5 全绿；**M2** pro 终态导出——`H1TodoExportService` + `PlannerHarnessExecutor` doFinally 三态收束导出 + `L2StateStore.syncTodoExport` 全量对比 void（key `task.{goalHash8}.{baseTaskId}`，task→workspace / chat→user），Live `verify_pro_todo_export_live.py` P1–P6 全绿；**M3** session_search 收缩版——`SessionSearchTool`（task 会话 MAIN 注册 `sunshine_session_search`，scope=session 仅本会话正文）+ rag-service convId 过滤 + `ContextAssembler` task 跳过 L3 自动注入 + Nacos `react.session-search.enabled`，Live `verify_session_search_live.py` P1–P4 全绿；**M3 扩展 workspace ✅（2026-08-26）**——`SessionSearchTool` scope 扩为 `session|workspace`（workspace 时反查 workspaceId → `findTaskIdsByWorkspace` 展开 task 会话并**排除当前** + `workspace-max-convs` 截断默认 20）+ rag-service `conv_id IN [...]`（`ChatHistoryMilvusService`/`ChatHistoryRetrievalService`/`ChatHistoryController`/`HistoryRagClient` 四层 convIds 透传）+ Nacos `react.session-search.workspace-max-convs`；单测 `ChatHistorySearchExprTest` IN 表达式 + `SessionSearchToolTest` workspace 五例 + 全量 1313/1313 全绿；Live 同工作区 A/B 两会话端到端（A 落 body 标记 → B `scope=workspace` → 日志 `工作区跨会话检索 convs=1` + 模型复述标记）；[spec](docs/superpowers/specs/2026-08-14-task-list-memory-unification-design.md)）· **L2 kind 精简 ✅（2026-08-27）**——`reasoning`/`option`/`interim_conclusion`/`topic` 合并为 `process_note`（置信 0.65 / TTL 7 天），`VALID_KINDS` 12→9（profile/preference/goal/agreement/constraint/fact/decision/process_note/todo）；`ContextWritePolicy`/`ContextProperties.L2` 收敛为 `process-note-min-confidence`/`process-note-ttl-days`；Catalog `context.memory.extract`/`context.l2.extract` kind 白名单同步；存量四类行清理；scope=user|workspace 两维不变；单测 L2ExtractConfidenceTest 5/5 + L2ExtractServiceParseTest 8/8 + ContextPropertiesTest 3/3 全绿；**v27.1 时间指代归一化**——`context.memory.extract` 加硬规则 value 中相对时间（今天/明天/下周）换算绝对日期 `YYYY-MM-DD`，`{today}` 占位由 `buildSystemPrompt` 注入 `LocalDate.now()`，防止跨会话召回漂移指代；[spec](docs/superpowers/specs/2026-07-31-unified-context-compression-design.md) §6.2/§6.3.1/§6.3.2 v27）· **L3 增强 v26 ✅**（语义提取 / 相似度去重 / 定期维护 / task process 层向量化——`LLMSemanticExtractor` + Catalog `context.l3.semantic-extract` v2、turn-pair 攒批 N 轮/M 分钟、Milvus `layer`/`scene` 字段、**去重按 layer 隔离**（semantic/process 精炼段不与 body 跨层误删）、`deleteByFilter` 状态过滤、分层 TTL；**v26.2 body 层非全量 ✅**（2026-08-26）——写路径单入口 `L3IngestService.ingestTurnPair`，语义提取**按轮判定**（二维数组）作 body 置信门禁：abstain 轮 body+semantic 均不落库、仅重要轮双写，噪音不再长期污染向量空间；`agent.context.l3.body-gate-enabled` 默认 true，关 → 回退两路并存全量；运维重建端点保留显式全量 escape hatch；单测 orchestrator+rag 全绿 · Live `verify_l3_enhancement_live.py` V-L3-1~6 全绿）· **L3 摘要化与 L2 对账 v28 ✅（2026-08-27）**——**chat body 原文层退役**（`L3IngestService.ingestTurnPair/ingest/flush`：chat 场景不再写 layer=body，仅落 semantic 摘要 `sem:{conv}:{ts}:{i}`，消除「用户/回答一个 chunk 一个 chunk」零散结构；task 保留 body+process 供 `session_search` 深挖）；**语义提取摘要化（方案1）**——Catalog `context.l3.semantic-extract` v4：每段为摘要形式（合并同主题连续对话为一段、保留 ID/数字/时间关键细节、每轮 ≤2 段）+ 「已由 L2 结构化覆盖的内容 abstain」；**L2 写入对账（方案2）**——`LLMSemanticExtractor` 写 semantic 前读该用户 active L2 `stateValue` 集合，语义段整段包含某条 L2 值 → abstain（强命中，查询失败保守不拦截）；**chat 召回收敛**——`L3RecallService` layers body+semantic→仅 semantic；`listL3Entries` 对话面板仅 semantic 且 role 统一「Chunk」（`l3RoleLabel` 恒 Chunk）；Milvus `listByConv` 透出 layer；`ContextAdminService.reingest` chat 提示不写 body；单测 orchestrator 1431+rag 全绿、vue-tsc 通过；[spec §13.4.1](docs/superpowers/specs/2026-07-31-unified-context-compression-design.md)；[spec](docs/superpowers/specs/2026-07-31-unified-context-compression-design.md) §7.4/§13.4）· **Skill 可发现/触发分离（skill-sticky）✅**（**S-0** chat_message 落 `routing_skill_ids`/`routing_agent_ids` + 续跑/新建复用 `RoutingSeed`；**S-D** `context.skill-directory` 名+描述目录、召回不灌 overlay；**S-T** L0 短路 + triggered skillIds 全链路 + skill 工具 schema 召回；**S-1** 轻 sticky——`RoutingStickyService` L0 整表替换/无触发继承，agentIds 跨轮接续；**A-1/A-6** spawn 双轨——预定义 agent 子工具 = (tenant,kind) ∩ 声明、动态 sub `tool_ids` ⊆ 集 + 缺省同款集；**A-7** spawn-hint 工具清单渲染 + v3.13 抽屉 skill 加载步骤；Live `verify_skill_sticky_live.py`；**A-2/A-3/A-4** 租户——skill `tenant_id` 全链路、agent 写侧、picker 按 (tenant,kind) 集收敛（`/sets/all/tool-ids` 并集 + BFF 代理）；**S-C** 双阈值采纳——`SkillAdoptionService` trigger/candidate/δ + 目录提权 `（可动态加载）` + `sunshine_search_skills` 升级触发（落库 + 沙箱懒挂 + sticky 继承），分类器逐项置信契约线上 v2，Nacos `skill-adoption` 默认关；⏳ v3.6 retrieval 双层；[spec](docs/superpowers/specs/2026-08-12-skill-sticky-process-chain-design.md)）· **账本-视图治理（memory-ledger-view）✅**（**O1** fast 中断落板——`doFinally` 信号互斥收口：CANCEL/ON_ERROR → `persistInterruptSnapshot`（幂等 upsert），COMPLETE 保留 `finishAnswerStream` 落板；Live `verify_taskboard_interrupt_live.py` I1–I4 全绿；**O2** 语义 merge 随统一压缩 §6.4 落地（`L2SemanticMergeService` + Catalog `context.l2.merge`）；**O4** 账本重建校验——`ContextAdminService.verifyRebuild` 同源对账（复用 `L1Compressor` 分区 + `TokenEstimator`）+ 只读端点 `rebuild-check` + `verify_context_rebuild.py` 扫描/单会话/self-test，判定分级 ERROR（H1/H3/H4/H6）/WARN（S1–S6）；**O3** 写路由收敛——`ContextWritePolicy` 写路由矩阵单点：`route()`（kind×mode → writeL2/writeL3/scope/scene + reason 决策记录 + 路由决策 info 日志）+ `l2MinConfidenceFor`/`l2TodoGatePasses` 写入门禁 + `l2TtlDays`/`l3TtlDays` TTL 表；`ContextWritePath`/`L2ExtractService`/`L2StateStore`/`ContextMaintenanceService` 全部委托，路由结果回归一致（`ContextWritePolicyTest` 16 例 + `ContextWritePathTest` 3 例，clean 1274/1274 全绿）；[spec](docs/superpowers/specs/2026-08-24-memory-ledger-view-optimization-design.md)）· **压缩点延后项收口（§5.5 ①③⑥⑮）✅**（2026-08-26）——**同步推进 P**：assemble 超预算 → `L1Compressor.advanceCompressionPoint` 零 LLM 纯写库前移压缩点 + 本轮按新 P 重组；**②⑤⑦ ✅ 追加收口**（② L3 尾部定序 / ⑤ 跨轮压缩单入口架构核实满足；⑦ 幂等增益判定——`refreshSameValue` 同值零增益跳过写库）；**P/S 分离**——`far_folded_msg_ids`(P 退役边界) / `far_summarized_msg_ids`(S 已折叠子集，新列) 分离，间隙轮写路径异步补折叠（防同步退役轮信息丢失）；**Budget 退役并入**（§8.2）——压缩点模式 `applyBudgetAtPoint` 丢 L3 → 退役 Mid 进 P → 丢 Far 块，Near/L2 永不丢（滑动窗保持静默丢弃基线）；**≤10k 硬预算**——`task-post-compact-budget` + `enforcePostCompactBudget`（先降级最旧 Mid、再折叠最旧 Near 保底 1 轮）；**Tier 定序**——`PromptComposer` scope-prompt 静态前置 / nodePrompt 尾部；O4 `verifyRebuild` 补 `gapRounds` + S7 间隙漂移（H4 仅按 S 判定）；全量 1274/1274 全绿，Live `verify_context_rebuild.py` 扫描/self-test 全绿；[spec](docs/superpowers/specs/2026-07-31-unified-context-compression-design.md) §5.5/§8.2/§13.3 · [task-scene §4.2.1](docs/superpowers/specs/2026-08-01-task-scene-context-design.md)）· **chat 压缩点二期 ✅**（2026-08-26）——`L1Compressor.compressionPointActive` 启用面扩至 `chat×fast|pro`（workflow 仍退出）；Near/Mid 参数按 kind 分化（chat 4+4+Far / task 2+2+Far≤10k，`compression-point.chat-near-keep/mid-keep-rounds`），chat 无 ≤10k 硬预算、靠组装侧 Budget 退役并入收敛；`compressByCompressionPoint`/`advanceCompressionPoint` 同步按 kind 选参数；Live 验收 chat 9 轮对话 → `compression-point kind=chat` 日志、Near 不裁剪、折叠 2 轮进 far_summary（P=S gap=0）、L1 行 near_n=4/mid_n=4、rebuild-check 三会话全 PASS；单测 `L1CompressorCompressionPointTest`/`ContextAssemblerL1Test` 补 chat 4+4/无硬预算/advance 4 轮保底用例· **工具轮确定性 schema 行（⑫⑬⑭⑲）✅**（2026-08-26）——`StepMetadata` 加 `toolArgs`（`ToolArgsRenderer` 白名单标量，禁整段 payload）+ `toolExitCode`（`SandboxExitCodeHolder` 透传 exec 退出码），工具步收口落库；`ToolSchemaRenderer` steps JSON → `[toolName] keyArgs=… status=ok|fail|denied exit=? · result≤200 · refs=[path]`（写/改类省略 result 禁 patch 原文）；四处历史构建点（`ChatStreamContextFactory` 新建/续跑 · `ContextWritePath` · `ContextAdminService` rebuild/print）`SessionTurn.fromMessage` 统一附 schema 行；Near/Mid 渲染原样附加（零 LLM），task Mid 摘要改机械短结论（`extractShortConclusion` 前 2 句 ≤120 字）；**Near 完整过程装载 ✅**（2026-08-26）——`TaskProcessRenderer` 从 steps 渲染 think 推理全文 + tool 序列原文（写/改保留 patch 原文、读/执行 ≤200+refs）；`SessionTurn.processLines`（task 场景 `fromMessage` 填充）+ `ContextAssembler.toChatTurns` task 渲染完整过程，`l1OverBudget`/`applyBudgetAtPoint`/`trimByTokens` 预算估算按完整渲染内容计，超限仍走压缩点退役兜底；全量 1313/1313 全绿 + Live `task×fast` 端到端（think reasoning + rag 步落库、Near=2 装载、rebuild-check PASS））· **业务上下文权威层（business-context M0–M5）✅**（2026-08-26）——读侧装载：`BusinessContextAssembler` 闸门（开关 ∧ kind=chat ∧ scene 非空）→ 三块按 policy > task > prefs 序：**Policy**（resource-manager `/policies/active` + orchestrator 5min 缓存，租户精确 > `*` 平台默认 ∧ 生效窗 ∧ 最高 version）/ **business_task 召回阶梯**（同场景活跃时间窗 → 会话锚定 → 最近 1 条详情 + ≤top-k 目录；done/archived 不进 Prompt）/ **场景偏好**（`user_context_state` 扩 `biz_scene_scope`/`confirm_status`，preference ∧ confirmed ∧ 白名单装载）；`ReactExecutor` 资源召回后注入 `injectedBlocks`（落点 L1 之后、user 消息之前）；**M4 冲突仲裁**——`BizContextConflictArbiter`（有 scene ∧ 有 Policy/任务板权威参照 ∧ L3 非空 → LLM 判定并过滤矛盾断言，`{"filter":[{"snippet":"原文片段"}]}` 按段落移除，失败兜底 drop/keep + `BIZ_CONTEXT_CONFLICT` 审计）；Catalog `context.biz-scene.conflict-check`（`=== USER ===` 分 system/user）；`ReactExecutor` scene 提局部变量 + `AssembledContext.withL3MaterialBlock`；Nacos `agent.business-context`（含 `conflict-check.*`）默认关；单测 `BusinessContextAssemblerTest` 12 例 + `BizContextConflictArbiterTest` 9 例 + 全量 1355/1355 全绿；Live `verify_business_context_live.py` A–D + M4 多轮积累 L3 → 过滤 1 段冲突摘要 l3=111->0 + 审计送达；**M5 embedding 回退 + 场景双轨**——`biz_scene_definition` 加向量/来源/审核列；`SceneEmbeddingService`（DashScope 向量化 + 余弦匹配 + 索引缓存 + 懒回填；**事件循环线程安全**：embed HTTP 统一 boundedElastic + `Future.get`，修读路径 reactor-http 上 `block()` NonBlocking 异常）；读路径 `ReactExecutor.resolveBizScene` 未命中 → embedding 回退；写路径 `SceneWriteResolver` ① 路由种子 → ② embedding → ③ `SceneAutoCreateService` LLM 自动创建（Catalog `context.biz-scene.auto-create` + 防污染：≥2 轮/max-pending/rate-limit/相似度 0.85 抑制）；`pending_review` 仅嵌入检索不装载 Policy/任务板；前端双 Tab + 审核；Nacos `agent.business-context.scene-embedding.*`/`scene-auto.*` 默认关；单测 10 例（含事件循环线程回归）+ 全量 1379/1379 全绿；Live `verify_scene_dual_track_live.py` A–E 全绿；**M0 装配时序 ✅**——`AssembleRequest.deferL3` fast×chat 路由前仅底座（assemble l3=0）+ `AssembledContext.L3Anchor` 分区锚点，`ReactExecutor` 资源召回后 `ContextAssembler.attachL3` 装配 L3（排除 Near/Mid 覆盖 + 剩余预算裁剪，先于 M4 仲裁），pro/workflow 保持现状；单测 7 例 + 全量 1386/1386 全绿；[spec](docs/superpowers/specs/2026-08-13-business-context-authority-design.md)）· **目标对齐与失败预算（react-goal-alignment 4.7.7）✅**（2026-08-26）——`AgentRunState`（挂 bridgeId 生命周期，无状态中间件）+ `GoalAlignmentMiddleware`（MAIN-only，每 N 轮 think 把原始问题+任务进度摆回模型面前，工具闸门防连续纯 think 轰炸，Catalog `react.goal-check`）+ `FailureBudgetMiddleware`（`ToolResultEndEvent.state` 判定，ERROR 计数/SUCCESS 清零/INTERRUPTED 不计，同参数指纹 + 同工具双维度阈值，Catalog `react.tool-failure-budget` + timeline `timeline.steps.tool-failure-budget`）——**失败契约收口**：`SandboxAgentTools` 失败/异常统一输出 `[ERROR]` 前缀（AS 2.0 契约）使 exec 非零退出判为 ERROR state；中间件链 `ProcessingStepMiddlewareFactory.sharedChain()`（Goal → PSM → Budget，洋葱序下 FailureBudget 最内层先收 ToolResultEndEvent 标记、PSM 后查并换「连续失败」after）；Nacos `react.goal-check`/`tool-failure-budget` 默认关热切；单测 18 例 + 全量 1331/1331 全绿；Live `verify_goal_alignment_live.py` G1–G4；[spec](docs/superpowers/specs/2026-07-27-react-goal-alignment-design.md)）· **5.2 用量计量 阶段一 ✅（2026-08-27）**——token 落库闭环：llm-gateway `TokenUsageCollector`（非流式 usage【修 `ChatCompletionResponse.Usage` `@JsonProperty` snake_case 映射，原反序列化恒 null】/ 流式末尾 chunk usage 提取 / 缺失按 messages+流式字符估算 `estimated`）+ MQ 生产 topic=`llm-usage`（RocketMQ v5 proxy）+ orchestrator 消费落库 `llm_usage_record`（`11-sunshine-orchestrator.sql`，`call_site`/`run_id`/`round_id` 维度预留）+ 查询端点 `GET /api/usage/records|summary`（按 model 聚合）；单测 llm-gateway 37/37 + orchestrator 1393/1393 全绿；Live 端到端一次对话全链路各次调用（流式主对话 + 非流式辅助）均落库且 `estimated=false`（消费端稀疏流量退避延迟 1–2 分钟，最终一致可接受）；**阶段二 ✅（2026-08-27）**——`UsageDailyAggregationJob` 5min 删除重建聚合 `llm_usage_daily`（按日/租户/模型/call_site，est_cost 按 Nacos `sunshine.llm-usage.price` 模型单价估算）+ `GET /api/usage/daily`；`tenant_quota`（orchestrator CRUD + `/check` 校验单点：白名单 `model_not_allowed` / 月 token 上限聚合当月用量 `quota_exceeded`）+ llm-gateway `QuotaCheckClient` 请求前校验（30s TTL 缓存 + fail-open，`llm.usage.quota.enabled` 默认 false 热切，超限 429 OpenAI 兼容 `error.code`）；`/ops` 用量页（BFF 透传 + `OpsView` 用量/配额双 Tab：统计卡 + 模型排行 + 日趋势 + 配额管理）；单测 orchestrator 1403/llm-gateway 38 全绿 · Live 白名单外/月度超限均 429 + 明确错误码 + 开关关恢复放行；**后置**：5.3 `call_site` 链路透传后聚合/配额按调用点细化；[spec](docs/superpowers/specs/phase5-operation-openness-design.md) §3.5.2）· **5.3 多模型场景路由 ✅（2026-08-27）**——`CallSiteKey` 枚举 SSOT（sunshine-common：chat/plan/worker/tool-call/rewrite/summarize/subagent，禁自定义）+ 策略表 `model_route_policy`（resource-manager 模型注册表 SSOT，`20-sunshine-model-registry.sql` 种子 7 条 first-available）+ JPA CRUD/catalog routes + llm-gateway `ModelRegistryCache.routeModelFor`；**call_site 透传**——Agent 角色经 `LoadBalancedWebClientTransport` 请求体 JSON 注入（`ReActAgentFactory` 按 `AgentRole`：MAIN→chat/SUB→subagent/PLANNER→plan/WORKER→worker）+ `LlmGatewayClient` 内部辅助默认 summarize + `IntentRouter`=rewrite，`TokenUsageCollector` 从 request 读 callSite 落库；**model=auto 路由**——`ModelRouter.resolveEffectiveModel` 显式 model 直路由、auto/缺省按 call_site 查策略池选首个 enabled，**生效模型回写请求体**（用量计量按实际生效模型落库）；无策略 auto → 400 明确报错；热更新经 Redis `model-catalog-changed`；**语义缓存隔离**——auto 请求不入缓存（策略状态可变）+ key 含 call_site 防跨调用点串用；前端 `/models` 增「路由策略」Tab（BFF `/api/models/routes` keys/list/upsert/delete 透传）；单测 llm-gateway 45/45 全绿；Live `verify_model_route_live.py` R1–R7 全绿（池首路由/显式不回归/auto 无策略 400/用量 call_site 落库/CRUD/换序热更新 30s 内生效/auto 不入缓存）；**后置**：5.3.5 Grafana 面板 call_site×model（数据已就绪）；[spec](docs/superpowers/specs/phase5-operation-openness-design.md) §3.5.3）· **5.5 工具语义检索 ✅（2026-08-27）**——工具目录建 Milvus 索引（rag-service `ToolMilvusService` collection `sunshine_tool_index` 租户隔离 + **flush 落盘**保 BOUNDED 一致性即时可见 + `ToolIndexService` 复用 `EmbeddingService` + `/api/tool-index/sync|search`，Nacos `rag.tool-index.*`）+ retrieval 分层注入（orchestrator `ToolRetrievalService` 恒注入判定内置/沙箱/HITL + 内容指纹幂等同步 + Tier 0 名目录确定性渲染；`ToolRetrievalMiddleware` 每轮按最近 USER 消息检索 Top-K → `setActivatedGroups` 激活组；`DynamicToolkitFactory` retrieval 模式业务工具按 `tool:{id}` 组注册、恒注入未分组；`ReActSystemPromptResolver` MAIN 注入 Catalog `context.tool-directory` 模板；`ReActAgentRuntime` 首轮 `presetInitialToolGroups` 预置 + ctx 透传 tenant/kind；Nacos `tool-inject.mode` full 默认/retrieval、失败回退全量；单测 orchestrator 1424 + rag-service 167 全绿；Live `verify_tool_retrieval_live.py` T1–T4 全绿；[spec](docs/superpowers/specs/phase5-operation-openness-design.md) §3.5.5；后置：5.5.5 golden-set 工具选择评测）· 缺口见 `docs/implementation-plan.md`。

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
| **Planner-Executor（4.14）** | `PlannerHarnessExecutor`：Planner=ReAct 主 Agent（**单一循环**边规划边执行，S5 v4：无 full/hier 模式；细则在 Worker）→ Worker=工具调用（`forWorker()`）→ Planner 自判 → 综合回答；`PlanNotebook` + **Redis 单写** + 3 类显式触发重规划；**动态 Plan-Workflow 已完全舍弃**（`PlanWorkflowExecutor`/`WorkflowPlanner`/`PlanApproval` 源码与读侧兼容已删，阶段 D ✅）；依赖与落地顺序见 [specs/README](docs/superpowers/specs/README.md#活跃增量方案依赖与落地顺序2026-08-13)；详设 [rebuild](docs/superpowers/specs/2026-08-05-planner-executor-rebuild-design.md) |
| **意图路由** | [unified-routing v6](docs/superpowers/specs/2026-07-29-unified-routing-design.md)：**R-0～R-4 ✅**（用户显式 `fast`/`pro`/`workflow` + 双轨收集 + ResourceDispatcher；读侧兼容已去除，wire 仅 fast/pro/workflow；PlanWorkflow 源码残留已删）；延期：`intent.classifier` live 版本 bump |
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
