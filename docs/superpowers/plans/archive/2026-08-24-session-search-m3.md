# M3 实施计划：session_search 收缩版（body + scope=session）

> **日期**：2026-08-24 · **阶段**：任务清单记忆一体化 §9 M3
> **状态**：✅ 已实现（2026-08-24；Live `verify_session_search_live.py` P1–P4 全绿）
> **SSOT spec**：[task-list-memory-unification-design](../specs/2026-08-14-task-list-memory-unification-design.md) §9 M3 · [task-scene-context-design](../specs/2026-08-01-task-scene-context-design.md) §6.3/§6.4 · [unified-context-compression-design](../specs/2026-07-31-unified-context-compression-design.md) §5.5.7 L3 行

## 1. 目标

task 会话（`kind=task`）提供**按需恢复本会话历史正文**的工具 `sunshine_session_search`（body 层 + scope=session），**不进前缀**（走 tool_result 进 tail）。当 Near/Mid/Far、任务清单块、KV Memory todo 注入不足以回答「本会话早前说过/做过什么」时，模型主动调用该工具深挖原文。

依赖：**L3 task 通道**——task 会话消息已由 `ContextWritePath → L3IngestService` 全量 ingest 进 Milvus `sunshine_chat_history`（body 层，user+assistant 消息对），数据可用。

## 2. 关键决策

| # | 决策 | 依据 |
|---|------|------|
| D1 | **一期不重建 Milvus collection**（不加 `scene` 字段），`scope=session` 由检索 expr 追加 `conv_id == X` 过滤实现 | Milvus schema 不可变，加 scene 必须 drop+create+全量 re-ingest，风险高且与 M3 核心功能无关；scene 隔离（修复 chat 自动召回搜到 task 消息的既有污染）与 `scope=workspace` 一并归二期 |
| D2 | 工具名 **`sunshine_session_search`**（区别于 AgentScope 原生 `session_search`） | task-scene §6.4 v7 撞名约定：AS 原生=run 内 JSONL 检索；自研=跨轮 L3 正文恢复 |
| D3 | **注册面 = MAIN × `kind=task`**；chat / workflow / SUB / PLANNER 不注册；pro Worker 二期 | spec §2.2/§3「仅 task×fast\|pro；workflow 不注册」；一期先 fast MAIN（Worker 需独立会话工具审计上下文确认，二期）
| D4 | **task 会话跳过 L3 自动注入**（ContextAssembler 门禁），改为按需 session_search | task-scene §6.3/§6.4「task 只写不自动注入（读路由闸门）」；跨会话续接已由 KV Memory todo（M1/M2）+ fast 任务清单恢复块（M0）承接 |
| D5 | 开关 `agent.execution.react.session-search.enabled`，默认 **true**（task 场景专用，chat 不注册无影响） | 对齐 decision/async-tool 开关模式；Nacos SSOT |

## 3. 改动清单

| # | 文件 | 改动 |
|---|------|------|
| A1 | `rag-service/.../ChatHistoryMilvusService` | `search` 加 `convId` 可选参数，expr 非空时追加 `&& conv_id == "X"` |
| A2 | `rag-service/.../ChatHistoryRetrievalService` | `search` 加 `convId` 透传 |
| A3 | `rag-service/.../ChatHistoryController` | `/search` 读可选 `convId` 透传 |
| B1 | `orchestrator/.../context/l3/HistoryRagClient` | `search` 加 `convId` 参数并透传 rag-service |
| C1 | `orchestrator/.../agent/SessionSearchTool`（新） | AgentTool：`query` 必填 + `scope` 默认 `session`；从 `StepEventBridge.toolAuditContext` 取 conversationId/userId/tenantId；`scope=session` 调 rag-service search（convId 过滤）；返回 body 命中正文列表 |
| C2 | `orchestrator/.../agent/DynamicToolkitFactory` | `buildFromWhitelist` 透传 `conversationKind`；MAIN 且 `kind=task` 且开关开 → 注册 `SessionSearchTool` |
| C3 | `orchestrator/.../config/AgentExecutionProperties` | `React.SessionSearch` 开关 |
| D1 | `orchestrator/.../context/ContextAssembler` | `kind=task` 跳过 `l3RecallService.recall`（L3 自动注入仅 chat） |
| E1 | `docs/nacos/sunshine-orchestrator.yaml` | `react.session-search` 段；改后跑 `sync_nacos.py` + 重启 orchestrator/rag-service |
| F1 | 单测 | `SessionSearchToolTest`（参数/scope 路由/无审计上下文降级）、`DynamicToolkitFactory` 注册断言、`L3RecallService` convId 过滤 |
| F2 | `scripts/verify_session_search_live.py`（新） | 黑盒验收：task 会话内触发模型调用 session_search 并恢复早前正文 |
| F3 | 文档 | spec M3 ✅ / plan 归档 / implementation-plan / CLAUDE.md |

## 4. 验收

1. task 会话（fast）工具集含 `sunshine_session_search`；chat / workflow 会话不含。
2. 工具调用后返回**本会话**早前轮次命中正文（scope=session：`conv_id` 过滤），不含他会话内容。
3. `kind=task` 会话不再自动注入 L3 材料块（ContextAssembler 门禁）；chat 自动注入不受影响。
4. 无会话上下文（工具审计绑定缺失 / conversationId 空）时工具返回错误说明，不抛异常。
5. Live 脚本全绿；单测覆盖注册/路由/降级/过滤。

## 5. 测试

- 单测：`SessionSearchToolTest`、`DynamicToolkitFactoryTest`（若存在）/现有工厂测试补断言、`L3RecallServiceTest` convId。
- Live：`scripts/verify_session_search_live.py`（task 会话，模型调用 `sunshine_session_search` 恢复早前约定/正文）。
