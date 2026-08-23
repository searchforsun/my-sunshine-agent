# Task List Memory M2 — pro 终态导出（H1 未完成项 → KV Memory `todo`）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** pro（Planner-Executor）会话结束时，把 H1 `PlanNotebook.taskQueue` 的**未完成项**结构性导出到 KV Memory（`kind=todo`，scope 按会话 `kind` 分流：task→workspace / chat→user）。新会话（同 workspace / 同用户）经 M1 已就绪的 KV 注入通道跨会话召回「未完成任务」，不丢 goal。

**Architecture:** 复用 M1 全部基础（`user_context_state` scope 列 / `L2StateStore.upsert(Workspace)` / `context.memory.extract` 参数化 / 读写闸门）。M2 新增的是**确定性结构导出**（非 LLM 抽取）：`PlannerHarnessExecutor.execute` 的 `loop.run` `doFinally`（success / error / cancel 三态）触发 `H1TodoExportService.export(notebook, ctx)`——`taskQueue.snapshotQueue()` 中 status ∈ {pending, in_progress, fail} 视为未完成，生成 Candidate `(todo, task.{goalHash8}.{baseTaskId}, label, 1.0, background=goal, active)`；key 以 **goal 稳定 hash + baseTaskId** 编码保证跨会话同 goal 复用同 key。`L2StateStore` 新增 `syncTodoExport`（按 scope 全量对比：本次未完成 key 集合之外的 `task.` 前缀 active 行显式 void）——天然覆盖「全部完成」「换题（goal 变化）」「重复沉淀幂等」三种语义，且只管理 `task.` 前缀、不触碰 LLM 抽取产生的其他 domain todo。

**Tech Stack:** Java 17 / Spring Data JPA / JUnit5 + Mockito + AssertJ · Python3 live 脚本（requests）

**Spec:** [task-list-memory-unification-design](../specs/2026-08-14-task-list-memory-unification-design.md) §3 核心设计 / §5.3 KV Memory todo 类 / §6 沉淀通道 / §9 M2 / §10 验收 · [planner-executor-rebuild](./2026-08-05-planner-executor-rebuild-design.md)（H1 SSOT · v17） · [unified-context-compression](./2026-07-31-unified-context-compression-design.md) §6.3（v22 门禁）

## Global Constraints

- **不双写**：执行中（worker 运行中 / planner 流式中）零记忆层写入；仅在 assistant 消息终态（loop.run 收束）导出一次
- **单写通道**：执行态（H1 taskQueue）→（导出）→ KV Memory，方向唯一；KV 从不写回 H1
- **只管理 `task.` 前缀**：`syncTodoExport` 的 void 全量对比仅限 `kind=todo AND state_key LIKE 'task.%'`，不触碰 LLM 抽取产生的其他 domain todo（如 `approval.*`）
- **key 跨会话稳定**：`task.{goalHash8}.{baseTaskId}`——goalHash 由 `originalGoal` SHA-256 前 8 hex 派生，同一 goal 跨会话同前缀；baseTaskId 用 `TaskItem.stripRetrySuffix` 去版本后缀
- **未完成定义**：status ∈ {pending, in_progress, fail}；done / cancelled / obsolete 一律不导出（换题时 `applyFollowUpGoalChange` 已把旧任务标 obsolete）
- **落库走 M1 通道**：task 会话 → `upsertWorkspace`（workspaceId 经 conversation 反查）；chat 会话 → `upsert`（user 维度）
- **导出即幂等**：同一 goal 重复导出以 key 覆盖（updated_at 刷新）；全量对比保证不残留

## Files

| 文件 | 动作 | 说明 |
|------|------|------|
| `orchestrator/.../plan/harness/H1TodoExportService.java` | 新增 | pro 终态结构导出器（notebook → Candidates → KV） |
| `orchestrator/.../plan/harness/PlannerHarnessExecutor.java` | 修改 | `loop.run(...).doFinally` 增加导出触发 |
| `orchestrator/.../context/l2/L2StateStore.java` | 修改 | 新增 `syncTodoExport` / `syncTodoExportWorkspace`（全量对比 + void 过期） |
| `orchestrator/.../context/l2/UserContextStateRepository.java` | 修改 | 新增 `state_key` 前缀查询（active + `task.` 前缀） |
| `orchestrator/.../conversation/ConversationService.java`（如需） | 可能修改 | 确认现有 `getOwned` 已够反查 workspaceId |
| `orchestrator/src/test/.../H1TodoExportServiceTest.java` | 新增 | 导出器单测 |
| `orchestrator/src/test/.../L2StateStoreFilterTest.java` | 修改 | `syncTodoExport` 单测 |
| `scripts/verify_pro_todo_export_live.py` | 新增 | M2 live 验收 |

## Task 1: `L2StateStore.syncTodoExport`（全量对比基础）

**Interfaces:**
- Produces: `syncTodoExport(String userId, String tenantId, List<Candidate> pending, Instant now)` — user 维度全量对比导出
- Produces: `syncTodoExportWorkspace(String workspaceId, String tenantId, List<Candidate> pending, Instant now)` — workspace 维度
- Consumes: repository 前缀查询（Step 2）

**Step 1: 写失败单测**（`L2StateStoreFilterTest` 追加）：

```java
@Test
void syncTodoExport_upsertsPending_voidsStaleTaskPrefixRows() {
    // 已有 active 行：task.a1b2c3d4.t1（本次仍 pending → 保留/刷新）、task.a1b2c3d4.t2（本次不再出现 → void）、approval.xxx（非 task 前缀 → 不动）
    // pending = [Candidate(todo, "task.a1b2c3d4.t1", "部署 QA 环境", 1.0, "目标", "active")]
    // 断言：t1 刷新 active；t2 置 void；approval.xxx 保持 active
}

@Test
void syncTodoExportWorkspace_pendingEmpty_voidsAllTaskPrefix() {
    // workspace 维度 pending 为空 → 该 scope 下所有 task. 前缀 active 行置 void
}

@Test
void syncTodoExport_doesNotTouchNonTaskPrefix() {
    // 非 task. 前缀 active 行不受影响
}
```

- [x] **Step 1: 写失败单测**
- [x] **Step 2: `UserContextStateRepository` 前缀查询**

```java
List<UserContextStateEntity> findByUserIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
        String userId, String tenantId, String kind, String prefix, String status);
List<UserContextStateEntity> findByWorkspaceIdAndTenantIdAndKindAndStateKeyStartingWithAndStatus(
        String workspaceId, String tenantId, String kind, String prefix, String status);
```

- [x] **Step 3: `L2StateStore.syncTodoExport` 实现**

```java
public void syncTodoExport(String userId, String tenantId, List<Candidate> pending, Instant now) {
    if (!StringUtils.hasText(userId)) return;
    syncTodoInternal(null, userId, tenantId, pending, now);
}
public void syncTodoExportWorkspace(String workspaceId, String tenantId, List<Candidate> pending, Instant now) {
    if (!StringUtils.hasText(workspaceId)) return;
    syncTodoInternal(workspaceId, null, tenantId, pending, now);
}
private void syncTodoInternal(String workspaceId, String userId, String tenantId,
                              List<Candidate> pending, Instant now) {
    // 1. 本次未完成 key 集合
    Set<String> pendingKeys = ...;
    // 2. 全量对比：该 scope 下 task. 前缀 active 行，key 不在 pendingKeys → void
    // 3. pending 内逐条 upsert（复用既有 upsertInternal 语义：同 key+value 刷新，否则 Merger）
}
```

**关键语义**：void 用既有 `voidActiveRow` 等价逻辑（不新增并行 void 路径）；`pendingKeys` 空 → 全部 task.* active 行 void；`pending` 为空列表时不执行 upsert 循环。

- [x] **Step 4: 编译绿 + 单测绿**

## Task 2: `H1TodoExportService`（结构导出器）

**Interfaces:**
- Produces: `export(PlanNotebook notebook, ExecutionStreamContext ctx)` — 从 notebook.taskQueue 导出未完成项到 KV
- Produces: `static String goalHash(String goal)` — SHA-256 前 8 hex（可单测）
- Consumes: `PlanNotebook.snapshotQueue()` / `TaskItem.stripRetrySuffix` · `L2StateStore.syncTodoExport(Workspace)` · conversation 反查 workspaceId

**Step 1: 写失败单测**（`H1TodoExportServiceTest`）：

```java
@Test
void export_taskConversation_persistsWorkspaceScopeTodo() {
    // notebook: kind=task, originalGoal="部署 QA-2026-0817", taskQueue=[t1 pending, t2 done]
    // ctx: kind=task, conversationId=conv-1；conversationRepo 反查 workspaceId=ws-1
    // 断言：l2StateStore.syncTodoExportWorkspace("ws-1", "default", [Candidate(todo, task.{hash}.t1, ...)], now) 被调用；不含 t2
}

@Test
void export_chatConversation_persistsUserScopeTodo() {
    // kind=chat → syncTodoExport(userId, ...) 被调用
}

@Test
void export_onlyPendingInProgressFail() {
    // taskQueue=[t1 pending, t2 in_progress, t3 fail, t4 done, t5 cancelled, t6 obsolete]
    // → 仅 t1/t2/t3 导出
}

@Test
void export_taskWithoutWorkspace_skips() {
    // conversationId 反查不到 workspaceId → 不调用导出，不抛错
}

@Test
void goalHash_isStableAndScoped() {
    // 同 goal 同 hash；不同 goal 不同 hash；前 8 hex
}
```

- [x] **Step 1: 写失败单测**
- [x] **Step 2: 实现 `H1TodoExportService`**

```java
@Slf4j @Service @RequiredArgsConstructor
public class H1TodoExportService {
    private final L2StateStore l2StateStore;
    private final ChatConversationRepository conversationRepo;

    public void export(PlanNotebook notebook, ExecutionStreamContext ctx) {
        if (notebook == null || ctx == null) return;
        String goal = ... (originalGoal / userQuery);
        String goalHash = goalHash(goal);
        List<TaskItem> queue = notebook.snapshotQueue();
        List<Candidate> pending = queue.stream()
            .filter(t -> t != null && UNFINISHED.contains(t.status()))
            .map(t -> new Candidate("todo", "task." + goalHash + "." + TaskItem.stripRetrySuffix(t.taskId()),
                    t.label(), 1.0, goal, "active"))
            .toList();
        if ("task".equals(ctx.conversationKind())) {
            String wsId = resolveWorkspaceId(ctx);
            if (!StringUtils.hasText(wsId)) { log.warn(...); return; }
            l2StateStore.syncTodoExportWorkspace(wsId, ctx.tenantId(), pending, Instant.now());
        } else {
            l2StateStore.syncTodoExport(ctx.userId(), ctx.tenantId(), pending, Instant.now());
        }
    }
    // 注意：导出是异步触发（@Async 或由调用方异步），goal/queue 快照在导出时已稳定
}
```

**value 自解释**：`TaskItem.label` 已是任务描述（如「部署 QA-2026-0817 环境」）；若 label 为空则跳过该项。

- [x] **Step 3: 编译绿 + 单测绿**

## Task 3: `PlannerHarnessExecutor` 触发

**Interfaces:**
- Consumes: `H1TodoExportService.export(notebook, ctx)`

**Step 1: 触发点**（`execute` 中 `loop.run` 之后）：

```java
return loop.run(ctx, notebook)
        .doFinally(signal -> {
            store.renewTtl(sessionId);
            exportTodoQuietly(ctx, notebook);
        })
        .onErrorResume(e -> fallbackOrPropagate(ctx, e));

private void exportTodoQuietly(ExecutionStreamContext ctx, PlanNotebook notebook) {
    try {
        todoExportService.export(notebook, ctx);
    } catch (Exception e) {
        log.warn("[H1TodoExport] 导出失败 session={}: {}", notebook.getSessionId(), e.getMessage());
    }
}
```

**Step 2: 失败/降级不阻断**——`fallbackOrPropagate` 路径不额外导出（loop.run 的 doFinally 已覆盖三态）。

**Step 3: 确认 `@Async`**——`H1TodoExportService.export` 直接调用（同步 DB 写，线程模型与既有 `store.save(notebook)` 一致），`doFinally` 内阻塞可接受（既有 renewTtl 已在此阻塞）；若实测影响流式收尾则改 `@Async` 独立线程。

- [x] **Step 1: `doFinally` 触发 + 日志**
- [x] **Step 2: 编译绿 + 全量相关单测绿**

## Task 4: live 脚本 `verify_pro_todo_export_live.py`

**场景（plan §10 验收 T1–T5 对齐）：**

| # | 场景 | 步骤 | 预期 |
|---|------|------|------|
| P1 | task 会话 pro 未完成任务导出 | 建 kind=task + workspaceId 会话，发 pro 任务（如「规划并执行：部署 QA-2026-0817 环境」），等会话完成 | `user_context_state` 出现 scope=workspace + kind=todo + key 前缀 `task.` 行，status=active |
| P2 | 同会话续跑幂等 | 同一会话再发一条 pro「继续」 | 原 key 仍 active（updated_at 刷新，行数不涨） |
| P3 | 跨会话召回 | 同 workspace 另建 task 会话发「继续上次任务」 | 模型回复引用未完成项关键词（行为证据）；DB 断言 todo 行存在 |
| P4 | 全完成即 void | 新 pro 会话显式完成全部任务（或语义上让 planner 完成） | 该 workspace 下 `task.` 前缀 active 行置 void（key 不再 active） |
| P5 | chat 会话 pro 导出 | chat 会话发 pro 任务 | scope=user + kind=todo + `task.` 前缀行 |
| P6 | 存量兼容 | chat 普通 fast 对话 | 既有 L2（preference 等）仍正常注入 |

**验收红线：** 执行中（pro 流式未结束）KV 无 `task.` 写入（等待窗口检查）；完成后行数仅按导出语义增长（幂等不膨胀）；`task.` 前缀 void 不触碰其他 domain todo。

- [x] **Step 1: 编写脚本**（参照 `verify_kv_memory_todo_live.py` 登录/建会话/发消息/查库惯例；pro 用 `executionMode=pro`；`sunshine_lib.run_mysql` 查 `user_context_state`）
- [x] **Step 2: 执行并全绿**（orchestrator `scripts/start.py --restart orchestrator`）
- [x] **Step 3: 文档同步**（spec M2 状态 / plan 归档 / implementation-plan / CLAUDE.md 进度行）

## 验收（Spec §10 M2 行）

| 项 | 预期 | 判据 |
|----|------|------|
| pro 续接 | ANSWER/终态后新会话召回未完成项，不丢 goal | Live P1/P3 |
| 不双写 | 执行中零 KV `task.` 写入 | Live 红线 |
| 完成即 void | 全完成/换题后 `task.` 前缀 active 行失效 | Live P4 + 单测 |
| 幂等 | 同 goal 重复导出不膨胀 | Live P2 + 单测 |

## 风险与默认假设

1. **`doFinally` 阻塞写**：既有 `store.renewTtl` 已同步阻塞；`export` 为同步 JPA 写，量大（taskQueue ≤ 20）可接受；如实测影响流式收尾，改 `@Async` 并透传 sessionId 内部重载 notebook。
2. **workspaceId 反查失败**：conversation 已删除或非 task 会话 → 跳过导出，仅日志；不阻断用户路径。
3. **goalHash 稳定性**：`originalGoal` 在同一 goal 的跨会话续跑中可能因 `applyFollowUpGoalChange` 微变（换题才变）；换题时旧前缀由全量对比 void（不残留）。
4. **`task.` 前缀冲突**：仅 H1TodoExportService 使用 `task.` 前缀；LLM 抽取的 todo key 由模型自定（prompt 示例为 `task.qa_env_deployment`，含 `task.` 前缀时可能被全量对比误伤——**prompt 已约束 key 由模型自定**，若实测 LLM 产出 `task.*` 前缀，则本 plan 增加 `task.kv_` 前缀区分或 prompt 加「禁止 `task.` 前缀」。默认假设：prompt 中 todo key 示例改为非 `task.` 域，避免与结构导出冲突）。
5. **M2 范围**：不做「会话级去重」（KV 与同会话恢复块只注入最近一层）——spec 标记 M1 后独立项，不阻塞。
