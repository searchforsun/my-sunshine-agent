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
| TD-140 | P1 | open | IntentRouter / RewriteConversationContext / ContextMessageBuilder | AssembledContext 三重格式化旁路；合并行为面大，下轮单独 scope |
| TD-141 | P3 | open | sandbox `formatEditUnifiedDiff` / `formatDiffLinesAsText` | editDiff 死代码与 detail 双写；产品路径已收口 |
| TD-157 | P3 | open | `implementation-plan.md` 缺口段 | 三份未实施 spec（dynamic-context-compression / workflow-structured-io / expert-as-subagent-A2A）状态未统一登记 |
| TD-162 | P0 | open | `sys_user.github_token` / `gitlab_token` | Git PAT 明文落库（`10-sunshine-auth.sql`）；2026-08-04 用户决策「先明文」，加密/外置凭据延后单独 scope |
| TD-163 | P2 | open | `MainLayout.vue`(1456 行) / `ChatView.vue`(2445 行) | 上帝组件（模板/样式混排）；拆分是跨模块大动作，下轮单独 scope |
| TD-164 | P2 | open | `specs/2026-07-31-unified-context-compression-design.md` | 基线压缩已 ✅；登记「Layer1 待恢复」表述过时——剩余为 §5.5+ 压缩点增强（设计稿），非 Layer1 整层缺失 |
| TD-167 | P3 | open | `SandboxAgentTools.java` L207 / `MemoryProperties.DEFAULT_SUMMARY_PROMPT` + `HarnessAgentFactory.resolveSummaryPrompt` | Catalog 缺失兜底默认文案残留（`sandbox.budget-exhausted` 与 `compaction.summary-prompt` 已有 seed，缺省不触发）；按「删兜底」纪律可进一步收敛 |
| TD-168 | P1 | open | Catalog DTO `ModelCapabilities`/`ModelCatalog*` 四方平行（rm/orch/gw/ui） | 2026-08-10 已统一 `defaults().toolCall=true` 与 Crypto/SceneKey SSOT；完整 DTO 生成/上收 common 延后 |
| TD-169 | P1 | open | `OperationStack.vue` `buildRoundGroupLabel` | 本地 sandbox 步骤话术 Map；应走后端 summary/label |
| TD-170 | P2 | open | `useModelsPage.ts` / `ModelsView.vue` / `ModelSceneResolver` | 上帝类拆分（按 Provider/Definition/Scene；Resolver Fetch vs Resolve） |
| TD-171 | P2 | open | `ModelWindowCache` + `ModelWindowCacheBridge` | 窗口双路径；应只信 Catalog |
| TD-172 | P3 | open | `model.crypto.aes-key` 默认串 | 生产应 fail-fast / 强制 env，禁默认材料 |
| TD-184 | P2 | open | `DecisionCard.vue` (~847 行) | 状态机+多题 UI+CSS 上帝组件；拆 `useDecisionForm` / QuestionList（R5 延后） |
| TD-185 | P2 | open | `DecisionLabels` `{choice}` 旧模板键 | Catalog 改 `{answers}` 后删兼容键 |
| TD-186 | P3 | open | Labels.bind 样板（17×） | 中期统一 `TimelineLabelFacade`；本轮不合并 |

### 文档债

| ID | 严重度 | 状态 | 位置 | 摘要 |
|----|--------|------|------|------|
| DOC-101 | P3 | open | `plans/2026-07-21-corpus50-platform-adapt.md` 等历史 plan | 仍写 TenantUserStore/`/mock-data`；实现期清单，可读但非 SSOT |
| DOC-102 | P3 | open | `specs/plans/2026-07-29-multi-agent-unified.*` | 历史对照仍大量使用 expert/peer 措辞（peer-collab 已删、spawn_subagent 已落地）；非本轮代码范围，建议后续文档轮次收敛术语 |
| DOC-103 | P1 | open | `CLAUDE.md` vs `executionModes.ts` | 已纠偏为「routing v6 设计中 / 现状 auto\|react…」；落地 fast/pro/workflow 时删本条 |
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
| TD-105 | 2026-07-17 | 删 `SandboxSessionLifecycle.openIfNeeded`；bridge 废弃 no-op / 未用 getter |
| TD-107 | 2026-07-17 | 删未接线 `grepAfterWithPath`（Properties + Nacos） |
| TD-108 | 2026-07-17 | 删 edit 旧 `<<< old` 解析；Binding 5 字段兼容构造 |
| TD-106 | 2026-07-17 | 沙箱时间线 SSOT：后端 headerPath/glob 推断 + metadata；前端停二次加工 |
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
| TD-100 | 2026-07-21 | 三服务 Admin 鉴权收敛 `BizAdminAuth`（common） |
| TD-101 | 2026-07-21 | `MockDataView` 拆 `bizTableSchema` + `useBizDataPage` + `BizDataView` |
| TD-102 | 2026-07-21 | 路由 `/mock-data`→`/biz-data`；token 默认 `sunshine-biz-admin-dev`；删 `VITE_MOCK_ADMIN` 兼容 |
| TD-104 | 2026-07-21 | 删一次性 `sync_corpus50_platform.py`（Live 已无旧 Catalog ID） |
| TD-107 | 2026-07-21 | `sync_nacos.py` 补漏 `sunshine-oa.yaml`（否则 OA admin-token 永不更新） |
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
