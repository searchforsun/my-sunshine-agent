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
| TD-009 | 代码债 | P1 | orchestrator + sunshine-ui | `ProcessingStep.status` 双写 + 前端 `lifecycle ?? status` fallback | open | 单独一轮：停写 status → 删 fallback → 清会话 |
| TD-010 | 代码债 | P2 | skill-manager | `GET /catalog` 与 `/catalog/index` 重复 | open | 确认无外部调用后删旧路径 |
| TD-011 | 代码债 | P2 | orchestrator + sunshine-ui | 上帝类：`WorkflowExecutor`(913) / `ChatController`(822) / `processingSteps.ts`(1310) | open | 按垂直链路拆分 |
| DOC-001 | 文档债 | P2 | docs/ | CLAUDE + locked-decisions + implementation-plan 规则重复 | open | 入口 ≤200 行 + ADR 归档 |
| DOC-002 | 文档债 | P2 | docs/superpowers/specs/ | timeline spec 仍引用已删 `ProcessingTimeline.vue` | open | 加 supersede 头或移 archive |
| ADR-001 | ADR | P2 | routing | locked-decisions「禁止推翻」与 P3 删兼容冲突 | open | 记录 `toLegacyIntentLabel` 等已删决策 |

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
