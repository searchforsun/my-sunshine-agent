# 技术债登记册

> 记录 **Deferred** 项、**文档债**、**ADR 待办**。  
> 清理流程：`/tech-debt-refactor`（原则见 `.cursor/commands/tech-debt-refactor.md` §0）。

## 使用方式

| 字段 | 说明 |
|------|------|
| ID | `TD-{序号}` 代码债 · `DOC-{序号}` 文档债 · `ADR-{序号}` 架构决策待写 |
| 严重度 | P0 安全/一致性 · P1 冗余/无扩展点 · P2 可读/文档失真 · P3 风格 |
| 状态 | `open` / `in-progress` / `done` / `wontfix` |

每双周消化 ≤3 条 P1；P0 立即排期。**文档债**与代码债同等优先级（冗长/重复的 SSOT 会助长不可维护性）。

## 类型说明

| 类型 | 示例 |
|------|------|
| 代码债 | 双入口、Deprecated 未删、重复 error map |
| 文档债 | CLAUDE 与 spec 重复、锁定决策与代码不符、进度夸大 |
| ADR 待办 | 需正式记录「为何打破旧文档约定」 |

---

## Backlog

| ID | 类型 | 严重度 | 模块 | 描述 | 状态 | 备注 |
|----|------|:------:|------|------|:----:|------|
| TD-009 | 代码债 | P1 | orchestrator + sunshine-ui | `ProcessingStep.status` 双写 + 前端 `lifecycle ?? status` fallback | done | 2026-06-29：SSE/落库停写 status；前端仅 lifecycle |
| TD-010 | 代码债 | P2 | skill-manager | `GET /catalog` 与 `/catalog/index` 重复 | done | 2026-06-29：删 `/catalog` |
| TD-011 | 代码债 | P2 | orchestrator + sunshine-ui | 上帝类：`WorkflowExecutor`(913) / `ChatController`(822) / `processingSteps.ts` | in-progress | 阶段1 ✅ C1 `ChatConfirmationController`；前端 ~499 行 |
| TD-012 | 代码债 | P1 | sunshine-ui | `chat.ts` 孤儿 `useChat()` | done | 2026-06-29：仅保留 `ChatMessage` 类型 |
| TD-013 | 代码债 | P2 | orchestrator | `WorkflowNodeLabels.isVisibleNode` 零引用 | done | 2026-06-29 |
| DOC-001 | 文档债 | P2 | docs/ | CLAUDE + README 规则重复 | done | 2026-06-29：入口 ≤200 行；命令去重；链 ADR |
| DOC-002 | 文档债 | P2 | docs/superpowers/specs/ | timeline spec 仍引用已删 `ProcessingTimeline.vue` | done | 2026-06-29 supersede 头 |

---

## 已完成（归档）

| ID | 完成日期 | 摘要 |
|----|----------|------|
| TD-001 | 2026-06-28 | 删 `LlmNodeHandler` + test（YAML 已全 `answer`） |
| TD-002 | 2026-06-28 | 删 `AgentStepSummarizer` + test |
| TD-003 | 2026-06-28 | 删 `completeReasoningRound` / `openThinkParallel` |
| TD-004 | 2026-06-28 | 删 `IntentRouter.classify` / `toLegacyIntentLabel` + test |
| TD-005 | 2026-06-28 | 删 `SkillCatalogClient.fetchCatalog` / `SkillCatalogService.allEntries` |
| TD-006 | 2026-06-28 | 删孤儿 `ProcessingTimeline.vue`（518 行） |
| TD-007 | 2026-06-28 | 删 `resolveStepExpandText` |
| TD-008 | 2026-06-28 | 删 `useUserId.ts` 垫片；`ChatView` 直引 `authStore` |
| TD-009 | 2026-06-29 | SSE/落库停写 step `status`；前端仅 `lifecycle` |
| TD-009-R4 | 2026-06-29 | `ProcessingStep` record 移除 `status` 字段；merger/构造全收敛 lifecycle |
| TD-010 | 2026-06-29 | 删 skill-manager `GET /api/skills/catalog`；显式 410 + `/catalog/index` SSOT |
| TD-012 | 2026-06-29 | 删 `chat.ts` 孤儿 `useChat()` |
| TD-013 | 2026-06-29 | 删 `WorkflowNodeLabels.isVisibleNode` |
| DOC-001 | 2026-06-29 | CLAUDE/README 去重；入口 ≤200 行；命令链 README |
| ADR-001 | 2026-06-29 | 锁定文档 vs 删兼容：ADR-001；回写 D3 catalog API |
