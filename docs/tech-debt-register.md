# 技术债登记册

> 记录 **Deferred** 项、**文档债**、**ADR 待办**。  
> 清理流程：`/tech-debt-refactor`（原则见 `.cursor/commands/tech-debt-refactor.md` §0）。

## 使用方式

| 字段 | 说明 |
|------|------|
| ID | `TD-{序号}` 代码债 · `DOC-{序号}` 文档债 · `ADR-{序号}` 架构决策待写 |
| 严重度 | P0 安全/一致性 · P1 冗余/无扩展点 · P2 可读/文档失真 · P3 风格 |
| 状态 | `open` / `in-progress` / `done` / `wontfix` |

每双周消化 ≤3 条 P1；P0 立即排期。**文档债**与代码债同等优先级。

---

## Backlog（open）

### 代码债

| ID | 严重度 | 状态 | 位置 | 摘要 |
|----|--------|------|------|------|
| TD-105 | P2 | open | `KbDocPanel.vue` (~1281 行) | RAG 分块预览门禁堆入上帝组件；下一轮 RAG scope 拆分 |
| TD-106 | P3 | open | finance/oa/hr `*BizService` | 三服务平行 CRUD 样板；暂不抽泛型 |
| TD-140 | P1 | closed | IntentRouter / ContextMessageBuilder | AssembledContext 三重格式化旁路；RewriteConversationContext 已删，此项已随意图改写退役收口 |
| TD-141 | P3 | done | sandbox `formatEditUnifiedDiff` / `formatDiffLinesAsText` | edit diff 产品路径已收口 `EditDiffBuilder`（HitlConfirmationService / SandboxToolExecutor 消费）：删 `HitlParamSupport.formatEditUnifiedDiff` + 私有辅助 + 测试 + `EditDiffBuilder` 注释引用；`formatDiffLinesAsText` 已不存在；12 单测绿 |
| TD-157 | P3 | done | `implementation-plan.md` / `specs/README.md` 缺口段 | 三份 spec 状态统一登记 + 归档引用同步：`workflow-structured-io` ✅ 已实现（4.13.8；README/spec 状态头更新）；`dynamic-context-compression` → 已归档（整合入 unified-context-compression）；`expert-as-subagent-A2A`（agent-team）→ 已归档/被否决（并入 multi-agent-unified）；implementation-plan 16 处 + README 10 处归档死链补 `archive/` 前缀 |
| TD-162 | P0 | open | `sys_user.github_token` / `gitlab_token` | Git PAT 明文落库（`10-sunshine-auth.sql`）；2026-08-04 用户决策「先明文」，加密/外置凭据延后单独 scope |
| TD-163 | P2 | open | `MainLayout.vue`(1456 行) / `ChatView.vue`(2445 行) | 上帝组件（模板/样式混排）；拆分是跨模块大动作，下轮单独 scope |
| TD-164 | P2 | done | `specs/2026-07-31-unified-context-compression-design.md` | 基线压缩已 ✅；spec 状态行已更新「基线管道 ✅ / §5.5+ 增强 ⬜ 设计稿」（§13.3 落地清单为验收依据）；「Layer1 待恢复」表述已不存在；archive 历史文件不在范围 |
| TD-167 | P3 | done | `SandboxAgentTools.java` L207 / `MemoryProperties.DEFAULT_SUMMARY_PROMPT` + `HarnessAgentFactory.resolveSummaryPrompt` | 删兜底：`sandbox.budget-exhausted` 死分支 `if(!hasText)` 删除（Catalog 已有 seed）；`compaction.summary-prompt` 回退链（config→Java 硬编码副本）删除，`resolveSummaryPrompt` 只读 Catalog（requireText 缺失 warn），`DEFAULT_SUMMARY_PROMPT` 常量与 `summaryPrompt` 字段删除；20 单测绿 |
| TD-168 | P1 | open | Catalog DTO `ModelCapabilities`/`ModelCatalog*` 四方平行（rm/orch/gw/ui） | 2026-08-10 已统一 `defaults().toolCall=true` 与 Crypto/SceneKey SSOT；完整 DTO 生成/上收 common 延后 |
| TD-169 | P1 | wontfix | `OperationStack.vue` `buildRoundGroupLabel` | 后端无「折叠区聚合统计」数据、SSE 无聚合契约；强行走后端需新增契约（收益低）。2026-08-22 删死参数 `_collapsedRounds`，话术 Map 保留为最简方案 |
| TD-170 | P2 | open | `useModelsPage.ts` / `ModelsView.vue` / `ModelSceneResolver` | 上帝类拆分（按 Provider/Definition/Scene；Resolver Fetch vs Resolve） |
| TD-171 | P2 | done | `ModelWindowCache` + `ModelWindowCacheBridge` | 收口双路径：删 `ModelWindowCache.refreshFromGateway`（绕经 llm-gateway，数据源同为注册表 Catalog）+ `LlmGatewayClient.listModels`/`ModelListDto`/`ModelInfoDto` 死代码 + 冗余转发层 `ModelWindowCacheBridge`（`syncFromResolver` 与 `syncFromRegistry` 重复，listener 直连 cache）+ `windowFor` 防御性 null 检查；构造签名单参；orchestrator 1085 单测绿 |
| TD-172 | P3 | done | `model.crypto.aes-key` 默认串 | 删两处 Nacos 默认串（`sunshine-llm-gateway.yaml` / `sunshine-resource-manager.yaml`）：`${MODEL_AES_KEY}` 强制 env，缺失启动失败（fail-fast）；2026-08-22 同步 Nacos 并设 `MODEL_AES_KEY` env 重启验证 |
| TD-184 | P2 | open | `DecisionCard.vue` (~847 行) | 状态机+多题 UI+CSS 上帝组件；拆 `useDecisionForm` / QuestionList（R5 延后） |
| TD-185 | P2 | wontfix | `DecisionLabels` `{choice}` 旧模板键 | 线上 `timeline.steps.decision.after` 实测为 `"用户已选择：{choice}"`（catalog_version=137）；Java 侧仅 `{choice}` 替换，无 `{answers}` 兼容键可删。2026-08-22 线上核实关闭 |
| TD-186 | P3 | open | Labels.bind 样板（17×） | 中期统一 `TimelineLabelFacade`；本轮不合并 |
| TD-194 | P3 | done | `ChatMessageEntity.execution_mode` 列 | v6 迁移后双列仅写不读：删 entity 字段 + `updateMessageExecutionPlan` 写入 + DDL 列；读侧 DTO/审计零消费，39 单测绿 |
| TD-195 | P3 | done | `sunshine-ui` `ExecutionPreference` 类型 / `useExecutionPreference` | 类型 `ExecutionMode` + 组合函数 `useExecutionMode`（文件改名）+ `isExecutionMode`/`normalizeExecutionMode`；读侧 DTO 属性 `executionPreference` 与 storage key 保留；ModelsView 存量 4 错已随 TD-204 修复，vue-tsc 全绿，单测绿 |
| TD-196 | P3 | done | 归档引用死链（全仓） | spec 归档 `archive/` 时未同步引用，全仓 278 处死链全部修复：核心文档 `implementation-plan.md`/`specs/README.md`/`CLAUDE.md` + 全部次级文档（103 文件 279/274 行）；880 链接复扫零死链；修复规则：`specs|plans` 段后补 `archive/`、archive 内回退 `../`、README 类按原 rel 目录语义匹配 `docs/<dir>/README.md` |
| TD-203 | P3 | done | `useConversationStreaming.ts` 孤儿 composable | 侧栏流式指示零引用（消费方已改走 `chatSessionRegistry`），删除；删除前全仓引用核实 |
| TD-204 | P3 | done | `ModelsView.vue` 存量 4 个 vue-tsc 错 | `boolParamOptions` value 由 boolean 改为 `'true'/'false'` 字符串 + `reasoningSplitSelectValue`/`includeUsageSelectValue` computed 桥接（运行时仍写回 boolean，`buildRequestExtras` 输出不变）；TD-195 遗留 4 错全消，vue-tsc 全绿 |
| TD-205 | P3 | done | `statusArchitecture.test.ts` 服务合并后拓扑未同步 | 期望值对齐 `SERVICE_DEFS`（11 个可探测服务；L4 上行 `Biz Simulator`、下行 MCP；Desensitize 并入 Resource Manager 描述）；5 测试全绿，前端全量 315 绿 |
| TD-206 | P3 | done | 无消费 re-export 清理 | `chatSessions.ts` 删 `appendChunk`/`SendOptions`/`SessionState` re-export（唯一外部消费 ChatView 仅用 `useChatSessions`）；`processingSteps.ts` 删无消费 `RewriteDetailView` re-export（保留 `TimelineMessageStatus`） |
| TD-207 | P3 | done | 前端 API 孤儿函数批量清理（21 函数） | 全仓 refs=1 扫描：`workspaceGit` 删 `createCheckout`/`removeCheckout`/`gitStatus`+`GitStatus` 接口（`ensureCheckout` 为幂等实现）；`executionPlans` 删 `listExecutionPlans`/`getExecutionPlanNodes`/`formatPlanStatus`/`formatTraceStatus`+`ExecutionPlanSummary`；`planHydrate` 删 `isPlanBizNodeStep`/`listPlanBizNodeSteps`；`processingStepsPause` 删 `retainIntentStepsOnly`；`taskBoardWaves` 删 `groupTaskBoardWaves`（+spec 对应 describe）；`chatSessionMutations` 删 `cloneStepsForReactive`（注释自称测试辅助但零消费）；`hitlSteps` 删 `syncPendingHitlFromSteps`（「兼容旧 API」注释）`/resolveHitlHint`/`resolveAgentSubStepsForDisplay`；`contentInterleave` 删 stub `leadingContentRows`（恒返回 []）；`sandboxEditDiff` 删 `formatDiffLinesAsText`（补齐 TD-141 前端侧）；`skills` 删 `updateSkillVersionSandbox`；`prompts` 删 `parseFragmentMeta`/`serializeFragmentMeta`；`ragAdmin/kbDocuments` 删 `ingestText`/`searchKnowledgePublic` + 连带删孤儿导出 `ragHeaders`（client.ts）。对应后端端点均为脚本/服务契约保留不动；vue-tsc 全绿，单测 311 绿 |
| TD-208 | P3 | done | `sunshine-ui/tsconfig.tsbuildinfo` 误跟踪 | vue-tsc 增量编译缓存被 git 跟踪（每次构建产生 `M` 脏状态）：`.gitignore` 增 `*.tsbuildinfo` + `git rm --cached` 取消跟踪；git 扫描无其它构建缓存产物（dist/.vite/.pyc/node_modules 均已被忽略） |
| TD-209 | P3 | done | `tool-service` `ExecutionModePolicy` 全链死代码 | `ExecutionModePolicyEntity`/`ExecutionModePolicyRepository`/`ToolRegistry`（纯转发 `InvokeRouter`，零消费）+ `ToolErrorCode.EXECUTION_MODE_POLICY_NOT_FOUND` + 建表 `docker/mysql/init/16-sunshine-tool-service.sql` `execution_mode_policy` 表全部删除；全仓零引用，tool-service 编译 + 42 单测绿 |
| TD-210 | P3 | done | `rag-service` `ConfigAdminMarker` 死占位接口 | package-private 空接口「T10–T12 实现」标注但全仓零引用，删除 |
| TD-211 | P3 | done | 前端 utils 孤儿函数批量清理（17 函数） | 全仓 refs=1 扫描：`workflowTemplates` 删 `getWorkflowTemplate`；`chatMention` 删 `segmentChatMentionsForMessage`/`hasChatMentionChips`；`skillMention` 删 `segmentSkillMentions`/`segmentSkillMentionsForMessage`/`hasSkillMentionSegments` + 类型/常量（`resolveSkillBindingForSend` 内联为 `findSkillByToken` 直取首个 token）；`agentMention` 删 `segmentAgentMentions`/`segmentAgentMentionsForMessage` + `AgentMentionSegment`；`workflowMention` 删 `segmentWorkflowMentions`/`segmentWorkflowMentionsForMessage` + `WorkflowMentionSegment`（`resolveWorkflowBindingForSend` 同法内联）；`kbConfigVersion` 删 `isSelectedVersionEvaluating`/`findActiveAppliedVersion`/`hasEvaluatingConfigVersion`/`canChangeConfigVersionStatus`/`isEvalJobLiveConfigVersion`/`canUseVersionActions`（连删后孤儿）；`evalConstants` 删 `isEvalNegativeCategory`；`workflowNodeParams` 删 `displayAgentKbId`/`displayRagKbId`/`agentKbIdEmptyLabel`；`workflowPlan` 删 `emptyWorkflowPlan`/`removeBusinessNode`；`sandboxPathChip` 删 `resolveSandboxPath`/`looksLikeSandboxRelativePath`；`workflowNodeIo` 删 `readToolParamValue`；`planGraph` 删 `linearNodeOrder`；vue-tsc 全绿，单测 311 绿 |
| TD-212 | P1 | done | `ContextWritePolicy.l2ExpiresAtFor` | 同一语义两个名字死方法：生产零调用（TTL 计算唯一入口为 `L2StateStore.expiresAtFor` → 委托 `l2TtlDays`）；删 Policy 方法 + 配套测试 + 无消费的 `contextProperties`/`@RequiredArgsConstructor`/`@Slf4j` 残注，类收敛为无状态 `@Component`；`L2StateStore.expiresAtFor` 加说明注释；17 单测绿 |
| TD-213 | P2 | done | `scripts/migrate_kv_memory_scope.sql` | 与 `docker/mysql/init/11-sunshine-orchestrator.sql` 的 `user_context_state` schema（scope/workspace_id/background + `idx_ctx_ws_kind_key_status`）完全重复，init 已是 SSOT；删除（历史 plan 提及为归档事实，不改写） |
| TD-214 | P2 | done | `scripts/migrate_biz_scene_dual_track.sql` | 与 `docker/mysql/init/19-sunshine-resource.sql` 的 `biz_scene_definition`（description_vector/source/source_conversation_id/approved_*/status v4）重复，init 已是 SSOT；删除 |
| TD-215 | P2 | done | `sunshine-ui/contextLabels.ts KIND_META` | 前后端漂移：KIND_META 仅 7 类缺 `process_note`/`todo`（后端 L2 已 9 类），L2 面板原样回退英文 kind；补 `process_note`（过程记录）/`todo`（待办）两类；vue-tsc 全绿 |
| TD-216 | P3 | wontfix | `ContextTaskH1Panel.vue` `roundStatusText` | 侦察初判「本地话术 Map 双轨」，复核后确认：为 H1 planner round 状态（done/fail/in_progress/cancelled/obsolete）专用展示映射，与 `STATUS_LABEL`（L2 memory active/superseded/void/conflict）为**不同枚举域**，且无重复消费方，属正当组件内展示函数；不迁移 |
| TD-217 | P3 | done | `ContextWritePolicy`/`ContextWritePath`/`L2ExtractService` 历史叙述注释 | 清理「收敛前分散决策 / O3：已收敛至 / 此前分散在 X」被否决方案对比描述，重写为正向描述当前写路由单点策略；`ContextWritePolicyTest` 类注释同步去「收敛前」；`LLMSemanticExtractor`「兼容旧一维数组」为输入变体说明，保留 |
| TD-218 | P3 | done | `ContextProperties.L3.maintenanceTtlDays` + Nacos `l3.maintenance-ttl-days` | 死配置：全仓无 `getMaintenanceTtlDays` 消费、与 `Maintenance.l3*TtlDays` 分层 TTL 语义重叠；同时删 Java 字段 + `docs/nacos/sunshine-orchestrator.yaml` 对应行 |
| TD-219 | P3 | done | `L2ConflictMerger.isElevatedKind` | 无生产调用死方法：`decide` 内联用 `ELEVATED_KINDS.contains(kind)`，helper 成孤儿；删除 |
| TD-220 | P1 | done | L2 kind 白名单收敛为单一 `enum ContextKind` | 新增 `context/l2/ContextKind`（9 类 wire 字面量 + 注入排序 + 审计具体性 + 高门槛子集单点）；替换 `L2ExtractService.VALID_KINDS`、`L2StateStore.KIND_ORDER`、`L2ConflictMerger.ELEVATED_KINDS`/`normalizeKind`、`ContextWritePolicy` 两处 switch、`L2RuleAuditor.kindSpecificity`、`H1TodoExportService` 字面量共 7 文件；TTL/置信门禁保留 `ContextProperties.L2` 数值外置（P6），仅解析收敛枚举；前端 `KIND_META` 注释标注同步义务；新增 `ContextKindTest` 5 例锁集合/排序/子集，L2 全套 + 上下文域回归全绿 |
| TD-221 | P2 | done | `ContextAdminService.verifyRebuild`（~200 行） | 抽为专类 `ContextRebuildVerifier`（@Component，注入 l1/l2/conversation/message 仓储 + TokenEstimator/ModelWindowCache/ModelSceneResolver）；`ContextAdminService` 回归 L1/L2/L3 CRUD 职责并移除 4 个失效依赖；Controller 直连新类；`ContextAdminRebuildCheckTest` 改测新类（8 用例全绿），行为零变化 |
| TD-222 | P2 | done | `L3IngestService.ingest` 平行写路径 + 6 参死重载 | **真实 bug**：`ContextAdminService.reingest` 仅 task 会话开放，但调 6 参 `ingest` 硬编码 scene="chat"，被内部 `"chat".equals(sc)` 短路 → task 重建恒 'ingested=0'。修复：删 6 参死重载，reingest 改传 `conv.getKind()`（=task），恢复 task L3 body 重建；27 单测绿 |
| TD-223 | P3 | done | `ContextAssembler.AssembleRequest` 5 个重载构造 | 收敛为单一 canonical 10 参构造：删除 4 个「兼容既有调用」缺省重载（P9 禁兼容性兜底）；生产 3 处（ChatStreamContextFactory×2 / ReactExecutor / HarnessPlanner）与测试 31 处调用点全部显式补全缺省值；`assemble_legacyConstructorDefaultsToChatScope` 随缺省语义消失而删除，`nonDefer` 测试更名为正向描述；ContextAssembler 三套测试 37 用例 + HarnessPlanner/ReactExecutor 全绿 |

### 文档债

| ID | 严重度 | 状态 | 位置 | 摘要 |
|----|--------|------|------|------|
| DOC-101 | P3 | wontfix | `plans/2026-07-21-corpus50-platform-adapt.md` 等历史 plan | 已全部 ✅ 的实现期清单；`TenantUserStore`/`/mock-data` 为当时实现事实（代码中已不存在，TD-189/190 收口），改动历史文档会失真；SSOT 已在 CLAUDE/README |
| DOC-102 | P3 | wontfix | `specs/plans/2026-07-29-multi-agent-unified.*` | 状态行已标注术语清理完成；expert/peer 仅存于 §0 术语重命名对照表（刻意保留的历史映射，删除会失去对照信息）；peer-collab 代码已删（TD-165） |
| DOC-103 | P1 | done | `CLAUDE.md` vs `executionModes.ts` | routing v6 已落地（wire 仅 fast/pro/workflow，`ExecutionPreference` 已删）；CLAUDE.md 已同步「存储读侧 DTO 字段仍名 executionPreference」；`normalizeExecutionMode` 的 auto/react→fast 为读侧归一化（spec 已覆盖），非 wire 兼容分支 |
| DOC-104 | P3 | open | `specs/2026-08-12-skill-sticky-process-chain-design.md` | v3.1：可发现≠触发；落地 S-0→S-D/S-T→S-1 后再归档；ledger/软链/图不做 |

**阶段三已知 WARN（非代码债）**：RAG v6 相对 vector +15% 提升轨未达标（见 `docs/rag/regression-*.md`）。

---

## 已完成（归档）

> 组内按 ID 升序排列。

### 代码债（TD）

| ID | 完成日期 | 摘要 |
|----|----------|------|
| TD-001 | 2026-06-28 | 删 `LlmNodeHandler` + test |
| TD-002 | 2026-06-28 | 删 `AgentStepSummarizer` + test |
| TD-003 | 2026-06-28 | 删 `completeReasoningRound` / `openThinkParallel` |
| TD-004 | 2026-06-28 | 删 `IntentRouter.classify` / `toLegacyIntentLabel` |
| TD-005 | 2026-06-28 | 删 `SkillCatalogClient.fetchCatalog` 等 |
| TD-006 | 2026-06-28 | 删孤儿 `ProcessingTimeline.vue` |
| TD-007 | 2026-06-28 | 删 `resolveStepExpandText` |
| TD-008 | 2026-06-28 | 删 `useUserId.ts` 垫片 |
| TD-009 | 2026-06-29 | SSE/落库停写 step `status` |
| TD-009-R4 | 2026-06-29 | `ProcessingStep` 移除 `status` 字段 |
| TD-010 | 2026-06-29 | 删 skill-manager 重复 `/catalog` |
| TD-011 | 2026-06-30 | 上帝类拆分（Chat/Workflow/processingSteps） |
| TD-012 | 2026-06-29 | 删 `chat.ts` 孤儿 `useChat()` |
| TD-013 | 2026-06-29 | 删 `WorkflowNodeLabels.isVisibleNode` |
| TD-014 | 2026-06-30 | 删重复 import |
| TD-015 | 2026-06-30 | 删 `AgentRunRequest.sub` deprecated 重载 |
| TD-016 | 2026-06-30 | `processingStepsNormalize.ts` |
| TD-017 | 2026-06-30 | 删 `migrateV1Step` |
| TD-019 | 2026-06-30 | 提取 `ChatStreamExecutor` |
| TD-020 | 2026-06-30 | 拆分 `WorkflowNodeRunner` / `WorkflowNodeFinalizer` |
| TD-021 | 2026-06-30 | 删 `normalizeTimelineSteps` 合成 think |
| TD-022 | 2026-06-30 | summary 主行不再 fallback label；`running()` 停双写 active |
| TD-023 | 2026-06-30 | `ChatView` 拆 5 composables（1694→1155 行） |
| TD-024 | 2026-06-30 | 拆分 `TimelineSession*`（Session ~280 行） |
| TD-025 | 2026-06-30 | 删 `ExecutionPlanParser.legacyPlan` |
| TD-026 | 2026-06-30 | 统一 `appendInterleavedContent` tail 锚点 |
| TD-027 | 2026-06-30 | 删 `migrateReasoningKeys` / `_idx_` |
| TD-028 | 2026-06-30 | 拆分 `PlanWorkflowPlanningRunner` / `ResumeRunner` |
| TD-029 | 2026-07-06 | 删 `IngestionController` + `ingestLegacy` |
| TD-030 | 2026-07-06 | 删 `EffectiveConfigService`，内联 `EffectiveConfigResolver` |
| TD-031 | 2026-07-06 | 删 `KbConfigOverride*` 全栈 + 前端 orphan API |
| TD-032 | 2026-07-06 | 删 `MilvusService` deprecated insert + `legacyMarkdown` |
| TD-033 | 2026-07-06 | 拆分 `DocumentCatalogService` → `DocumentChunkIndexer` + `DocumentVersionOps` |
| TD-034 | 2026-07-06 | 删 `KnowledgeRetrievalService` → `RagSearch` + 直调 `RagClient`；删 `RagClient` 旧 overload |
| TD-035 | 2026-07-06 | 删 `WorkflowCheckpoint` 2-arg 构造，调用方显式 `PausePhase` |
| TD-036 | 2026-07-06 | 删 `IntentLabels`/`TimelineLabels` 硬编码 fallback |
| TD-037 | 2026-07-06 | 删 `StepLabels` 与 Nacos 重复的 before/active/after switch；`SkillLoadLabels` 未 bind 抛异常 |
| TD-038 | 2026-07-06 | 拆分 `ProcessingStepMerger` → `ProcessingStepSerde` + `ProcessingStepLifecycleOps`（253 行） |
| TD-039 | 2026-07-06 | 删 `scripts/_tmp_planner_system.txt` |
| TD-041 | 2026-07-06 | 删 `sunshine-ui/src/api/knowledge.ts` orphan 兼容包装 |
| TD-042 | 2026-07-06 | 删 `ragAdmin.uploadDocumentMarkdown` deprecated alias |
| TD-043 | 2026-07-06 | 删 llm-gateway `/chat/completions/stream`；`LlmGatewayClient` 统一 `/chat/completions` + `stream:true` |
| TD-044 | 2026-07-06 | 删 `RagClient.parseSearchResponse` flat body 兼容 |
| TD-045 | 2026-07-06 | 补 `docker/minio/init/entrypoint-wrapper.sh`（MinIO compose 启动依赖） |
| TD-046 | 2026-07-06 | Flyway V14 react_pause + V15 conversation_kb_id 迁移对齐 |
| TD-047 | 2026-07-06 | `StepLabels.labelFor` 标准步标题收敛 Nacos `agent.timeline.*.label` + `TimelineStepLabels` |
| TD-048 | 2026-07-06 | 删 `evalConstants.normalizeEvalSuiteConfig` 嵌套 eval/productionGates 兼容 |
| TD-049 | 2026-07-06 | `ThinkStepIds.displayLabel` 收敛 Nacos `agent.timeline.steps.think` + `ThinkStepLabels` |
| TD-050 | 2026-07-06 | think before/active/after 收敛 Nacos `agent.timeline.steps.think`（含 follow-up / simple-llm modes） |
| TD-051 | 2026-07-07 | `PlanApprovalLabels` 收敛 Nacos `agent.timeline.plan-approval` + `PlanApprovalLabelService` |
| TD-052 | 2026-07-07 | tool/node 步骤 label/before/active/after 收敛 Nacos `agent.timeline.steps`（tool、node） |
| TD-053 | 2026-07-07 | 删 `HitlLabels` 静态 fallback；`HitlLabelService` 统一读 Nacos 模板 |
| TD-054 | 2026-07-07 | `StepSummarizer` agent/rag/plan/generate/skill 摘要收敛 Nacos + `SummaryStepLabelService` |
| TD-055 | 2026-07-07 | 新增 `TimelineStepId` 字符串枚举；processing/execution/audit 主路径停写标准步 id 字面量 |
| TD-056 | 2026-07-07 | 新增 `QueryRewriteScenario` 枚举；rewrite/RagClient 停写场景 id 字面量 |
| TD-057 | 2026-07-07 | Workflow 节点 type 展示名收敛 Nacos `agent.timeline.workflow-node-types`；删 `WorkflowNodeLabels` 静态 fallback |
| TD-058 | 2026-07-07 | `WorkflowNodeType` 全面替代 execution/plan 域 type 字面量比较 |
| TD-059 | 2026-07-07 | `AgentNodeDetailSummarizer` 摘要模板收敛 Nacos `agent.timeline.workflow-agent` + `AgentNodeDetailLabelService` |
| TD-060 | 2026-07-07 | Workflow 节点完成态摘要收敛 Nacos `agent.timeline.workflow-node-completion` + `WorkflowNodeCompletionLabelService` |
| TD-061 | 2026-07-07 | `WorkflowNodeLabelService.typeLabel` 改用 `WorkflowNodeType.of()` 解析（config 层保留 guarded switch 默认值） |
| TD-062 | 2026-07-07 | 工具结果摘要收敛 Nacos `agent.timeline.tool-result` + `ToolOutputSummaryKind` / `ToolResultLabelService` |
| TD-063 | 2026-07-07 | `RagNodeHandler`/`ProcessingStepHook`/`SummaryStepLabelService` 零命中判定收敛 `ToolResultLabels` |
| TD-064 | 2026-07-07 | 工具摘要/模板 SSOT 迁至 tool-manager（`summarize-output`/`summarize-rag-hits` + Nacos `tool.timeline.result`）；orchestrator 删 `ToolResultSummarizer`/`ToolResultLabels` 等本地实现，经 `ToolCatalogService` 调 API |
| TD-065 | 2026-07-07 | orchestrator 单测基建：`TimelineLabelJUnitExtension` 统一 bind 时间线模板；`GenerationJob*` 补 `bindStreamEpoch`；`commitFinal` 6 参断言对齐；删 orphan `RagDetailFormatter` |
| TD-066 | 2026-07-07 | 删 `KnowledgeRetrievalPipeline.debugSearch(EffectiveRagConfig)`；删 `ExecutionStreamContext.legacyIntent` 死字段 |
| TD-067 | 2026-07-07 | `StepEventBridge` 静态 Map → `StepEventBridgeRegistry` Spring 单例；静态门面委托 + `clearAll`/`resetRegistry` 可测 |
| TD-068 | 2026-07-07 | 拆分 `IntentLabelService`（790→~190）→ `ThinkStepLabelService` + `TimelineStepLabelService` + `TimelineLabelTemplates`；静态门面各绑专属 Service |
| TD-069 | 2026-07-07 | 拆分 `HitlConfirmationService`（656→~280）→ `HitlTokenRegistry` + `HitlTimelineBridge` + `HitlParamSupport`；`waitForDecision` 收敛四路径 await |
| TD-070 | 2026-07-07 | `SkillsView` 拆分：`useSkillsPage` + `useSkillFilePreview` + `skillsVersionUtils`；模板拆 `SkillsListPanel` / `SkillDetailPanel` / `SkillFormModals`；视图 2142→143 行；`npm run build` 通过 |
| TD-071 | 2026-07-07 | `ToolManagerClient` 全路径 Mono API（`invokeMono`/`summarizeOutputMono`/`summarizeByKindMono`）；客户端零 `.block()`；同步调用方在 boundedElastic 或 `ToolCatalogService` 边界 block |
| TD-072 | 2026-07-07 | 拆分 `StepMetadata`→`RagStepMetadataParser`+`StepMetadataAssembler`（482→116）；`AgentNodeHandler`→`RequestAssembler`/`AuditSupport`/`ResultBuilder`（538→128）；`GenerationJob`→`ChunkEmitter`+`CheckpointSupport`（528→384）；465 测试全绿 |
| TD-073 | 2026-07-07 | rag `EvaluateService`→`EvalFullRunOrchestrator`/`EvalSmokeRunner`/`EvalReportPersister`/`EvalRetrievalProbe`（746→328）；`ConfigVersionService`→`ConfigVersionStore`/`EvalLifecycle`/`PublishOps`（537→227）；rag-service 测试全绿 |
| TD-074 | 2026-07-07 | 前端域拆分：`ragAdmin/`（client/kbDocuments/kbConfig/eval + barrel）；`chatSessions`→`chatSessionRegistry`/`chatSessionMutations`/`chatSessionSseConsumer`（909→459）；`KbConfigPanel`→`useKbConfigPanel`（1049→513）；`npm run build` 通过 |
| TD-075 | 2026-07-08 | expert 发言流式：`step_delta(result)` 不切分 + 空白 token 勿用 `hasText` 过滤（根因非 Markdown normalizer） |
| TD-076 | 2026-07-08 | Synthesizer 流式：`StreamDeltaNormalizer` 闭合 `**` 勿按 `prev.startsWith(incoming)` 丢弃（OpenAI 增量 delta） |
| TD-077 | 2026-07-11 | 工具集 Tab：ReAct/Planner 启用与 SDK/MCP 池分离；新增 `plan-workflow` 工具集；运行时 `set ∩ pool` |
| TD-078 | 2026-07-11 | 删 `ToolsView.defaultPlanWorkflowPolicy` 硬编码；策略以 API/DB 为准 |
| TD-081 | 2026-07-11 | 工具集 Tab 模板抽 `ToolPoolGroupSection.vue`；SDK/MCP 双段重复 markup 去重 |
| TD-083 | 2026-07-11 | Catalog DTO 增 `enabled`；`refreshCatalog` 单次 fetch + `buildToolEnabledMap` |
| TD-084 | 2026-07-11 | 删 BFF 孤儿 `ToolManagerClient`；catalog 仅经 `ToolManagerAdminClient` |
| TD-085 | 2026-07-11 | `/api/tools/catalog` 从 `SkillsController` 迁至 `ToolsAdminController` |
| TD-087 | 2026-07-11 | 工具集成员制：空集默认、`members`/`picker` API、`critical` 合并进 plan-workflow 成员；前端 `ToolsetTabPanel` + 分页 + 添加弹窗 |
| TD-091 | 2026-07-11 | 删 legacy `GET/PUT .../sets/{react-default\|plan-workflow}` + `ToolSetAdminService` 整表替换 |
| TD-092 | 2026-07-11 | `patchTool` description `trim()` 非空校验 + `TOOL_DESCRIPTION_REQUIRED` |
| TD-093 | 2026-07-11 | 删 `ToolsView` 孤儿 `.tool-pool-*` / `.plan-policy-*` CSS |
| TD-082 | 2026-07-11 | Catalog DTO 增 `source`/`sourceRef`；前端 `filterCatalogBySource` 替代 id 前缀 |
| TD-086 | 2026-07-11 | 合并 `ToolCatalogClient`+`ToolSetClient` → `ToolManagerClient` |
| TD-080 | 2026-07-11 | `ToolsView` 拆 `useToolsPage`+面板/弹窗；再抽 `useMcpServerActions`（369 行） |
| TD-088 | 2026-07-11 | `ToolCatalogEntry` 迁至 `sunshine-common` SSOT；BFF `/api/tools/catalog` 类型化 |
| TD-089 | 2026-07-11 | Admin DTO（SDK/MCP/工具集）迁至 `sunshine-common`；tool-manager 删本地 `dto/` |
| TD-090 | 2026-07-11 | BFF `ToolsAdminController`/`ToolManagerAdminClient` 全量 `Mono<R<T>>` 类型化 |
| TD-094 | 2026-07-11 | 删 `loadToolEnabledMap` / `ToolSetConfig` 孤儿 API |
| TD-095 | 2026-07-15 | 拆 `useWorkflowsPage` → import + lifecycle（1381→913） |
| TD-096 | 2026-07-15 | Studio 校验：本地零延迟 + 服务端 WorkflowPlanValidator 权威 |
| TD-097 | 2026-07-15 | `WorkflowNodeType` SSOT 迁 sunshine-common |
| TD-099 | 2026-07-15 | 删 `WorkflowPlanValidator.validate()` 兼容门面 |
| TD-101 | 2026-07-15 | 详设对齐发布校验器 / DB Loader |
| TD-103 | 2026-07-15 | 随枚举迁移消除 sunshine-workflows 过时注释 |
| TD-098 | 2026-07-15 | 删 FE 节点默认静默兜底；orch fetch 失败保留上一份策略 |
| TD-100 | 2026-07-15 | Studio 大文件拆分：Admin Package/Support；layout metrics/loop；FlowNode visual；ExclusiveEdges |
| TD-102 | 2026-07-15 | 历史 orchestration 文档加 SUPERSEDED（Nacos workflow） |
| TD-104 | 2026-07-15 | Chat/Studio 画布边界：`workflowFlowProjection` 只读投影；删孤儿 PlanDagGraph / previewNodes |
| TD-107 | 2026-07-17 | 删未接线 `grepAfterWithPath`（Properties + Nacos） |
| TD-108 | 2026-07-17 | 删 edit 旧 `<<< old` 解析；Binding 5 字段兼容构造 |
| TD-111 | 2026-07-17 | `agent.sandbox.tools` SSOT；删 ToolCatalogService 硬编码 + AgentTools schema |
| TD-110 | 2026-07-17 | 沙箱 Policy/DTO SSOT：`com.sunshine.common.sandbox`；删 14 处模块内拷贝 |
| TD-109 | 2026-07-17 | 抽 `useSandboxFileTree` + `useSandboxPreviewTabs` + 子组件；抽屉 1031→~254 行 |
| TD-112 | 2026-07-17 | 抽 `useSandboxToolExpand` + `SandboxToolExpandPanel`；`OperationCard` 801→465 行 |
| TD-113 | 2026-07-18 | stepId cancel 校验 messageId 归属 |
| TD-114 | 2026-07-18 | sandbox cancel 绑定 session |
| TD-115 | 2026-07-18 | spawn 取消终态单写（Registry → GenerationJob） |
| TD-116 | 2026-07-18 | 沙箱 PostActing `consumeRecentlyCancelled`；禁中文门闩 |
| TD-117 | 2026-07-18 | UI 可取消跟 `metadata.cancellable` |
| TD-118 | 2026-07-18 | 删 sandbox cancel legacy SSE /「已暂停」同义词 |
| TD-119 | 2026-07-18 | 显式 `TokenWrapperMode`；route/drain 共用 `applyTokenWrapper`；spawn `PASS_THROUGH` |
| TD-120 | 2026-07-18 | `SpawnSubagentLabelService` + Labels 门面；`SubStepsFold` 供 spawn/Workflow 共用 |
| TD-122 | 2026-07-18 | 沙箱 pause 单写：Controller + expandDetail；`cancelResult` 不再 pause |
| TD-123 | 2026-07-18 | UI `sandbox__*` 前缀 + cancel 跟 lifecycle；删 `SANDBOX_TOOL_IDS` /「已取消」门闩 |
| TD-124 | 2026-07-18 | spawn 取消禁 Hook fallback |
| TD-125 | 2026-07-18 | register 强制 assistant messageId；禁 bridgeId 冒充 |
| TD-126 | 2026-07-18 | 抽 `SubAgentContentTokens`；Collector/Bridge 共用 content 路由 |
| TD-127 | 2026-07-18 | 删 `SpawnRunRegistry.timelineSupport` 死字段 |
| TD-128 | 2026-07-18 | 删 `formatForReplan(String)` / `WorkflowPlanner.replan(String)` |
| TD-129 | 2026-07-18 | FE 终态只信 SSE `after`；stop/interleave 禁「已取消」「已暂停」硬编码 |
| TD-130 | 2026-07-18 | interrupt 补终态 `flushCancelTerminal` 直写 GenerationJob；删 Support Hook `cancel` |
| TD-131 | 2026-07-20 | `/prompts` 拆 `usePromptList`/`VersionOps`/`RoutingRuleOps`/`DryRun` + `promptVersionUtils`；门面 ~233 行 |
| TD-132 | 2026-07-20 | `verify_prompt_catalog_live` P3 硬命中变化 + P5 orchestrator catalogVersion + P6 golden-set 子集 |
| TD-133 | 2026-07-20 | STM header/preamble/current-user-marker 迁 Catalog；删 Nacos `skill-overlays` 兜底 |
| TD-134 | 2026-07-20 | timeline Catalog 缺条目 → 空+warn；删 Java `defaultSteps` 生产兜底 |
| TD-135 | 2026-07-20 | `PlanAnswerPromptAssembler` 删 `DEFAULT_TEMPLATE`；缺 `answer.template` → 空+warn |
| TD-136 | 2026-07-20 | 清 Nacos 僵尸 `topology-hints` / 空 prompt 键；删 `systemPrompt`/`modeOverlays`/`cancelResult` 残留绑定 |
| TD-137 | 2026-07-20 | ReAct Toolkit 组装改 `boundedElastic`；`ToolManagerClient` 禁止在 reactor-http 上 `block` 静默空工具集 |
| TD-138 | 2026-07-20 | AgentScope ↑1.0.8（并行 tool `mergeSequential` 保序）；`completeRunningActive` 跳过 tool 步，避免空 after |
| TD-139 | 2026-07-20 | Plan 执行期 `NodeRetryPolicy` 改 boundedElastic；规划/执行错误分流，禁执行失败静默降级 ReAct |
| TD-142 | 2026-07-22 | 删 rag 孤儿 MTM（`MemoryMilvus*`/`MemoryController`/`MemoryRetrievalService`） |
| TD-143 | 2026-07-22 | 统一 `agent.context.l1.max-chars=120000`（Java 默认对齐 Nacos） |
| TD-144 | 2026-07-22 | 拆 `ContextAuditService` → `L2RuleAuditor` / `ContextLlmAuditClient` / `L1AuditApplier` |
| TD-145 | 2026-07-22 | 拆 `ContextView` → `useContextPage` + L1/L2/L3 Panel |
| TD-146 | 2026-07-22 | STM/MTM 命名残留清理（IntentRouter/GenerationJob/注释） |
| TD-150 | 2026-07-27 | 归档 `plans/2026-07-22-agentscope-2-upgrade.md`（兼容桥旧路线，SUPERSEDED 头） |
| TD-151 | 2026-07-27 | 归档 `plans/2026-07-23-agentscope-2-native-first-redesign.md`（P0-P3+P7 ✅ 注记） |
| TD-152 | 2026-07-27 | `specs/2026-07-23` 状态头改「已完成」+ `specs/README` AS2 行状态修正 |
| TD-153 | 2026-07-27 | `expert-as-subagent-design` 标注 E5 不采纳（内部 P6 统一否决，A2A 保留参考） |
| TD-154 | 2026-07-27 | `ReActAgentRuntime` checkpoint 保存失败 warn→error（含 userId/msgId）；删 `ReactCheckpointService` 死代码 `interrupt/saveCheckpoint` |
| TD-155 | 2026-07-27 | 注释去 legacy（ReActAgentRuntime/ProcessingStepMiddleware/SubAgentContentTokens/StreamToken） |
| TD-156 | 2026-07-27 | `StaticPlanAdapter.from` 迁测试源集 `StaticPlanAdapterTestSupport`，删生产 deprecated |
| TD-158 | 2026-08-04 | 删 `io/agentscope/` 死代码（2 文件 +1402 行，与 agentscope-2.0.0-sources.jar 逐字节相同、无构建引用） |
| TD-159 | 2026-08-04 | 删 `arti.png`（1.1MB 死二进制，零引用） |
| TD-160 | 2026-08-04 | 删 `skillMentionEditor.ts` `@deprecated` 别名 + `defaultMentionCatalogs` 死导出 + 3 个未用类型导入 |
| TD-161 | 2026-08-04 | `WorkspaceSandboxLifecycle` `auth-service.base-url` 默认值 `8210`→`8100`（配置漂移） |
| TD-165 | 2026-08-07 | peer-collab/expert 移除后遗留物全量清理：孤儿注解污染 `RoutingGoldenSetTest`、BFF `/api/audit/peer-run` 死端点 + `peerAudit.ts` 孤儿、`peer_run` 死表 DDL、`UnifiedRuleEngine.peer_phrase` 死分支、前端 peer/expert 展示 Map 与 `peer_phrase` 选项、`verify_prompt_catalog_live` 失效 peer 门禁、测试 fixture 与文案残留；服务端删 `sunshine_expert` 库 + `peer_run` 表；golden-set 文档 §E/§K 修订 |
| TD-166 | 2026-08-07 | 运行时硬编码提示词迁 Catalog：`ProcessingStepMiddleware` 收尾轮/软限额收束指令 → `mode-overlay.react-summary-turn`/`mode-overlay.react-soft-limit`；`ReactExecutor` spawn 委派提示 → `react.spawn-hint`（`{agents}`/`{agentId}` 模板）；`RagContextFormatter`/`RagTool` 工具结果格式与失败提示 → `rag.tool-result`（content_json，`{count}`/`{reason}` 模板）；`AgentGroundingProperties.rejectionMessage` 代码默认值去重（Nacos SSOT）；live DB catalog_version 67；前端 `PROMPT_KIND_LABELS` 补 `rag` |
| TD-180 | 2026-08-12 | decision/async 文档去双轨：删 active 重复 plan；✅ specs/plans 归档；D12 短 open stub |
| TD-181 | 2026-08-12 | `DecisionCard` 状态行删本地「决策 · *」表，只信 `summary.*`（缺省 title） |
| TD-182 | 2026-08-12 | `StepTimeline.afterTimeout/afterSkip` + `DecisionLabelService` 读 Catalog；decision seed v2 |
| TD-187 | 2026-07-21 | 三服务 Admin 鉴权收敛 `BizAdminAuth`（common） |
| TD-188 | 2026-07-21 | `MockDataView` 拆 `bizTableSchema` + `useBizDataPage` + `BizDataView` |
| TD-189 | 2026-07-21 | 路由 `/mock-data`→`/biz-data`；token 默认 `sunshine-biz-admin-dev`；删 `VITE_MOCK_ADMIN` 兼容 |
| TD-190 | 2026-07-21 | 删一次性 `sync_corpus50_platform.py`（Live 已无旧 Catalog ID） |
| TD-191 | 2026-07-17 | 删 `SandboxSessionLifecycle.openIfNeeded`；bridge 废弃 no-op / 未用 getter |
| TD-192 | 2026-07-17 | 沙箱时间线 SSOT：后端 headerPath/glob 推断 + metadata；前端停二次加工 |
| TD-193 | 2026-07-21 | `sync_nacos.py` 补漏 `sunshine-oa.yaml`（否则 OA admin-token 永不更新） |
| TD-197 | 2026-08-22 | `SkillCatalogService` 无参 `renderForClassifier()`/`renderIntoClassifier(String)` 兼容入口（仅测试引用，生产只走 kind 路径）删除；`includeAll` 布尔参数内联；测试迁移 `renderForClassifier("chat")`；orchestrator 全量 1085 单测绿 |
| TD-198 | 2026-08-22 | 注释精确化：`ConversationDetailDto`「读侧映射旧 wire」→「DTO 字段名沿用读侧旧名 executionPreference，值域 wire 三值」；`ExecutionPlanParser`「兼容旧 classifier」→「L3 分类器输出契约」；`ToolNodeHandler`/`ExecutionPlanParser` 均无残留兼容分支（核查后保持） |
| TD-199 | 2026-08-22 | 删恒真死方法 `ExecutionMode.isForced()`（三模式均钉死，v6 无 auto 自判）→ 调用处 `ChatStreamContextFactory` 简化为 `!preference.allowsSkillBinding()`；`ModelWindowCache.refresh` 删冗余 `new ConcurrentHashMap<>(...)`（`Map.copyOf` 已生成不可变副本）；orchestrator 1085 单测绿 |
| TD-200 | 2026-08-22 | 删 `IntentRouter.classifyPlan(String)` 兼容重载（仅传用户句的调用方已无）；`SkillDiscoveryService.enrich(plan, userMessage)` 删死参数 `userMessage`（实现仅 `sanitizeSkillPlan(plan)`）；`ExecutionPlanRouter` 两处链式简化（`enrich(plan)` + `filterForTrack(plan, preference)` 直传）；测试同步；全仓零残留引用 |
| TD-201 | 2026-08-22 | `RoutingContext` 收口：删死构造 7-arg（含 memory+lockedMode，零引用）+ 死静态方法 `of(String)`（零引用）；`executionMode()` 删枚举→字符串→枚举无意义往返（`ExecutionMode.from(preference.wireValue())` → 直接返回 `preference`）；routing 相关 68 单测绿 |
| TD-202 | 2026-08-22 | `WorkflowCatalog.renderForPrompt()` 无参重载删除（仅测试引用，生产全走 `renderForPrompt(sessionKind)`；与 TD-197 同构），测试迁移 `renderForPrompt(null)`；前端删 3 个孤儿组件：`SkillMentionChip.vue`（仅包装 MentionChip 的 skill 场景）、`WorkspaceSelector.vue`（工作区选择模态）、`ReasoningPanel.vue`（思考过程面板，chat-meta 已由时间线取代）——全仓零引用，vue-tsc 仅存量 ModelsView 4 错 |
| TD-199 | 2026-08-22 | 路由域收口：删 `ExecutionMode.isForced()` 恒真方法（调用处 `!allowsSkillBinding()` 等价简化）；`SkillDiscoveryService.enrich` 死参数 `userMessage` 删除；`ExecutionPlanRouter` `filterForTrack` 冗余三元 `from(wireValue())` 恒等简化；`IntentRouter.classifyPlan(String)` 无生产调用方死重载删除；orchestrator 1085 单测绿 |
| TD-212 | 2026-09-01 | 删 `ContextWritePolicy.l2ExpiresAtFor` 死方法（生产零调用，TTL 唯一入口= `L2StateStore.expiresAtFor`）+ 配套测试 + 无消费的 `contextProperties`/`@RequiredArgsConstructor`/`@Slf4j` 残注；类收敛无状态 `@Component`；`L2StateStore.expiresAtFor` 加说明注释；17 单测绿 |
| TD-213 | 2026-09-01 | 删 `scripts/migrate_kv_memory_scope.sql`（与 init 11 `user_context_state` schema 完全重复，init 为 SSOT） |
| TD-214 | 2026-09-01 | 删 `scripts/migrate_biz_scene_dual_track.sql`（与 init 19 `biz_scene_definition` 重复，init 为 SSOT） |
| TD-215 | 2026-09-01 | `contextLabels.ts KIND_META` 补 `process_note`/`todo`（对齐后端 L2 9 类，消除前后端漂移）；vue-tsc 全绿 |
| TD-217 | 2026-09-01 | 清理 `ContextWritePolicy`/`ContextWritePath`/`L2ExtractService` 及其测试类中「收敛前分散/O3 已收敛至」被否方案对比注释，重写正向 |
| TD-218 | 2026-09-01 | 删 `ContextProperties.L3.maintenanceTtlDays` 死配置 + Nacos `l3.maintenance-ttl-days` 行（全仓无消费） |
| TD-219 | 2026-09-01 | 删 `L2ConflictMerger.isElevatedKind` 无生产调用死方法 |
| TD-220 | 2026-09-03 | L2 kind 收敛为 `enum ContextKind`（`context/l2/ContextKind`：wire 字面量/注入排序/审计具体性/高门槛子集单点）；替换 `VALID_KINDS`/`KIND_ORDER`/`ELEVATED_KINDS`/`normalizeKind`/两处 TTL·置信 switch/`kindSpecificity`/`H1TodoExportService` 字面量共 7 文件；TTL/置信数值保留 `ContextProperties.L2` 外置；新增 `ContextKindTest` 5 例 |
| TD-221 | 2026-09-03 | `verifyRebuild`（~200 行）抽为专类 `ContextRebuildVerifier`；`ContextAdminService` 回归 CRUD 职责并移除 4 个失效依赖；Controller 直连新类；8 用例改测新类全绿，行为零变化 |
| TD-222 | 2026-09-02 | 修复 `ContextAdminService.reingest` task 重建恒 0 bug：删 `L3IngestService.ingest` 6 参死重载（写死 chat 被短路），reingest 改传 `conv.getKind()`；27 单测绿 |
| TD-223 | 2026-09-03 | `AssembleRequest` 收敛为单一 canonical 10 参构造：删 4 个兼容缺省重载（P9），生产 3 处 + 测试 31 处调用点显式补全；删 legacy 构造专项测试；37 用例全绿 |

### 文档债（DOC）

| ID | 完成日期 | 摘要 |
|----|----------|------|
| DOC-001 | 2026-06-29 | CLAUDE/README 去重 |
| DOC-002 | 2026-06-29 | timeline spec supersede |
| DOC-003 | 2026-06-30 | phase3 §4 与 §0/§6 对齐 |
| DOC-004 | 2026-06-30 | phase3 实施计划加 supersede |
| DOC-005 | 2026-06-30 | 覆盖度审计加 supersede + 结论更新 |
| DOC-006 | 2026-06-30 | multi-agent plan/design 进度更新 |
| DOC-007 | 2026-06-30 | timeline spec 移 `docs/archive/` |
| DOC-010 | 2026-06-30 | Phase1/2 REQ 移 `requirements/done/` |
| DOC-011 | 2026-07-01 | E2E 对齐 OperationStack V2；`mock-server` 补 auth；`e2e/helpers.ts` |
| DOC-012 | 2026-07-06 | `backlog.md` 与代码对齐 legacy API 删除 |
| DOC-013 | 2026-07-07 | CLAUDE 索引化：架构/端口/中间件链 README；运维 SSOT 留 README |
| DOC-014 | 2026-07-07 | `implementation-plan` 已完成阶段压缩为 SSOT 索引；保留阶段三检查门 + 阶段四缺口 |
| DOC-015 | 2026-07-07 | `rag/backlog.md` 标 supersede（排期走 plan + rag/README）；留 4.1/4.2 检查门留档 |
| DOC-016 | 2026-07-07 | 路由 Nacos 规则块从 phase2-closure plan/design 移除；SSOT 链 `routing-golden-set.md` |
| DOC-017 | 2026-07-07 | CLAUDE 进度段更新 TD-064～074、代码债 Backlog 已空 |
| DOC-018 | 2026-07-07 | TaskBoard spec：`tasks` 步文案改 Nacos timeline + `TimelineStepLabelService`（非本地 displayName Map） |
| DOC-019 | 2026-07-08 | 多专家协作（4.7.3）文档闭环：CLAUDE/README/implementation-plan/expert-consultation/peer-collab/routing-golden-set/phase4 标 ✅ |
| DOC-020 | 2026-07-09 | TaskBoard 文档：Timeline `think→tasks→tool`、Hook 锚定 think、prompt/Hook 职责分离、merge content 去重、`max-iters` SSOT |
| DOC-021 | 2026-07-09 | ReAct Hook：无业务 tool 间隔的连续 think 合并；终态避免多个「综合分析」行 |
| DOC-022 | 2026-07-17 | 5 份已完成 sandbox plan → `docs/superpowers/plans/archive/`（ARCHIVED 头 + 链修复） |
| DOC-023 | 2026-07-18 | spawn design §5：Nacos 键改为 `agent.timeline.steps.subagent` + `agent.execution.react.subagent.*` |
| DOC-024 | 2026-07-18 | sandbox cancel plan 对齐 `SandboxInvocationRegistry` / `cancellable`；去幽灵 toolUseId/Budget |
| DOC-025 | 2026-07-20 | 4.11 Catalog SSOT 文档收口；timeline-summary design 与实现对齐 |
| DOC-100 | 2026-07-21 | corpus50 design 数据层 supersede → biz-db-crud；路由 `/biz-data` |
| DOC-026 | 2026-07-22 | Context 文档勘误（写序 L2→L1、maxChars、类名）；废止稿归档 `specs/archive/`；autocontext 去 STM 误导 |

### 架构决策（ADR）

| ID | 完成日期 | 摘要 |
|----|----------|------|
| ADR-001 | 2026-06-29 | 锁定文档 vs 删兼容 |
