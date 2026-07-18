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
| TD-119 | P1 | open | `StepEventBridgeRegistry` spawn 空 wrapper + `sub-` 入队 | 与 Workflow 非空 wrapper 契约分裂；宜显式 TokenWrapperMode |
| TD-120 | P2 | open | `SpawnSubagentTimelineSupport` / Labels static / Workflow Bridge 近拷贝 | 薄门面与静态单例；可选合并 fold 原语 |
| TD-121 | P2 | open | `PlanValidationFeedback` + `maxNodes+6` | 中文 regex→错误码；魔法上限宜进 Nacos / 结构化 Issue |

**2026-07-18 本轮已消化（ef49f08..）**：TD-113（stepId cancel messageId 归属）；TD-114（sandbox cancel session 绑定）；TD-115（spawn 取消终态单写 B）；TD-116（沙箱 PostActing `consumeRecentlyCancelled`、禁中文门闩）；TD-117/118（UI `metadata.cancellable`、删 legacy SSE/`已暂停` synonym）；DOC-023/024（spawn Nacos 键 + cancel plan 对齐实现）。

**2026-07-17 TD-110 已消化**：沙箱 `SandboxPolicy` + 5 RPC DTO 迁至 `com.sunshine.common.sandbox`；删 orchestrator/skill-manager/sandbox-service 六处拷贝；`SandboxPolicyDto` 合并为 `SandboxPolicy`。

**2026-07-17 TD-111 已消化**：`agent.sandbox.tools` 承载 displayName/description/schema；`ToolCatalogService` + `SandboxAgentTools` 读 `AgentSandboxProperties`。

**2026-07-17 TD-112 已消化**：抽 `useSandboxToolExpand` + `SandboxToolExpandPanel`；`OperationCard` 801→465 行。

**2026-07-17 沙箱债 TD-106 已消化**：后端 `headerPath` + glob 结果推断搜索根 + `metadata.sandboxPath/sandboxSearchRoot`；前端主行只截断 `summary.after`。

**2026-07-17 沙箱债本轮已消化**：TD-105（删 `openIfNeeded` + bridge 废弃 no-op）；TD-107（删未接线 `grepAfterWithPath`）；TD-108（删旧 `<<< old` 解析 + Binding 5 字段构造）；DOC-022（5 份 sandbox plan → `plans/archive/`）。

**2026-07-15 续轮 3 已消化**：TD-104（抽 `workflowFlowProjection`；Chat 仅依赖投影；删孤儿 `PlanDagGraph` / `buildPreviewDagNodes`）。

**2026-07-15 续轮 2 已消化**：TD-100（`WorkflowAdminSupport` + `WorkflowPackageService`；`workflowDagLayoutMetrics` / `workflowLoopLayout`；`workflowFlowNodeVisual` + PropsAside exclusive）。

**2026-07-15 续轮已消化**：TD-098（删 FE `FALLBACK_NODE_DEFAULTS` + orch 误导性 code fallback 日志）；TD-100 部分（`WorkflowExclusiveEdgesSection`）；TD-102（历史 orchestration 文档 SUPERSEDED）。

**2026-07-15 Workflow Studio 本轮已消化**：TD-095（`useWorkflowsPage` → import/lifecycle）；TD-096（本地零延迟 + 服务端发布权威契约澄清）；TD-097（`WorkflowNodeType` → `sunshine-common`）；TD-099（删 `WorkflowPlanValidator.validate` 兼容门面）；TD-101（详设 PlanValidator/Loader 文案对齐）；TD-103（过时 sunshine-workflows 注释随枚举迁移消除）。

**2026-07-11 本轮已消化**：TD-077/078/081/083/084/085/087（工具集语义）；**TD-091/092/093**（legacy API、description 校验、死 CSS）；**TD-082/086/080/094**（sourceRef Catalog、Client 合并、ToolsView 拆分、孤儿 API）；**TD-088**（`ToolCatalogEntry` SSOT + BFF catalog 类型化）；**TD-089–090**（Admin DTO 迁至 `sunshine-common`、BFF 全量类型化）；**TD-080**（抽 `useMcpServerActions`，`useToolsPage` 369 行）。

### 文档债

| ID | 严重度 | 状态 | 位置 | 摘要 |
|----|--------|------|------|------|
| DOC-023 | P1 | done | spawn design §5 | 2026-07-18：Nacos 键改为 `agent.timeline.steps.subagent` + `agent.execution.react.subagent.*` |
| DOC-024 | P1 | done | sandbox cancel plan | 2026-07-18：对齐 `SandboxInvocationRegistry` / `cancellable`；去掉幽灵 toolUseId/Budget 勾选 |

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

### 架构决策（ADR）

| ID | 完成日期 | 摘要 |
|----|----------|------|
| ADR-001 | 2026-06-29 | 锁定文档 vs 删兼容 |
