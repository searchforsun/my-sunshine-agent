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

| ID | 类型 | 严重度 | 模块 | 描述 | 状态 | 备注 |
|----|------|:------:|------|------|:----:|------|
| TD-023 | 代码债 | P2 | sunshine-ui | `ChatView.vue` 上帝组件（~1694 行） | open | 拆 composables |

**阶段三已知 WARN（非代码债）**：RAG v6 相对 vector +15% 提升轨未达标（见 `docs/rag/regression-*.md`）。

---

## 已完成（归档）

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
| TD-025 | 2026-06-30 | 删 `ExecutionPlanParser.legacyPlan` |
| TD-026 | 2026-06-30 | 统一 `appendInterleavedContent` tail 锚点 |
| TD-027 | 2026-06-30 | 删 `migrateReasoningKeys` / `_idx_` |
| TD-022 | 2026-06-30 | summary 主行不再 fallback label；`running()` 停双写 active |
| TD-024 | 2026-06-30 | 拆分 `TimelineSession*`（Session ~280 行） |
| TD-028 | 2026-06-30 | 拆分 `PlanWorkflowPlanningRunner` / `ResumeRunner` |
| DOC-010 | 2026-06-30 | Phase1/2 REQ 移 `requirements/done/` |
| DOC-001 | 2026-06-29 | CLAUDE/README 去重 |
| DOC-002 | 2026-06-29 | timeline spec supersede |
| DOC-003 | 2026-06-30 | phase3 §4 与 §0/§6 对齐 |
| DOC-004 | 2026-06-30 | phase3 实施计划加 supersede |
| DOC-005 | 2026-06-30 | 覆盖度审计加 supersede + 结论更新 |
| DOC-006 | 2026-06-30 | multi-agent plan/design 进度更新 |
| DOC-007 | 2026-06-30 | timeline spec 移 `docs/archive/` |
| ADR-001 | 2026-06-29 | 锁定文档 vs 删兼容 |
