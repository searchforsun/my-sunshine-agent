# Planner-Executor H-7 Live + 验收前置 Implementation Plan

> **状态**：✅ 代码已落地（2026-08-13；分支 `feature/planner-h7-live`）· Live 需部署后跑 `verify_planner_executor_live.py`  
> **Spec**：[rebuild §7 / §9.2](../specs/2026-08-05-planner-executor-rebuild-design.md) · [routing v6 R-4 仍延期](../specs/2026-07-29-unified-routing-design.md)  
> **前置**：H-0～H-6 ✅（[kernel](./2026-08-13-planner-executor-kernel.md) · [routing v6+H-5](./2026-08-13-unified-routing-v6-h5.md) · [H-6 frontend](./2026-08-13-planner-h6-frontend.md)）  
> **本 plan 不做**：阶段 D / R-4 删 `PlanWorkflow*` 源码；完整 4.7.7 Middleware；Decision D12 Planner；压缩点 / task-scene 深改；H1 **LLM** 折叠（现网确定性截断可过 P6 soft）  
>
> **Commits**：`679c8e8f` Task1 投影 · `85983318` tasks/handoff/answer/follow-up/audit · (+ live script + docs)  
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐阻塞 §9.2 Live 的 harness SSE/契约缺口，落地 `scripts/verify_planner_executor_live.py`（P1–P8），使专业模式可全量验收；**不**删除旧 Plan-Workflow。

**Architecture:** Loop 在 plan/worker 状态变更时投影 H1→`tasks` 步（复用 `StepMetadata.withTasks` / ReAct TaskBoard 通道）；worker `done` 步写入 handoff 摘要；follow-up 更新 `originalGoal` 并标记受影响 task `obsolete`；薄审计 `plan.worker_*`；Live 脚本对 SSE/Redis/回归做硬检查。

**Tech Stack:** Java 17 / JUnit5 · Vue 前端已就绪（H-6）· Python live 脚本（对齐 `verify_react_taskboard_live.py` / `verify_spawn_subagent_live.py`）· Redis · Nacos

## Global Constraints

- **SSOT**：[rebuild](../specs/2026-08-05-planner-executor-rebuild-design.md) §4 / §5.4 / §9.2；检查门以本 plan 为准，**勿**引用已归档 harness v8 §12.2 编号
- **禁止阶段 D**：不得删除 `PlanWorkflowExecutor` / `WorkflowPlanner` / `PlanApproval*` / Catalog `plan-workflow.*` 源文件
- **`harness.enabled=true`** 现网已开；`pro`→harness；关则 pro 显式失败
- **模型输出不二次加工**：不对 Planner/Worker 正文截断/摘要兜底；H1 投影只映射 status 枚举，不改 label 语义
- **TaskBoard**：一级 = H1 `taskQueue` 投影；**禁止**再调 LLM 生成看板；二级仍靠 Worker 内 `manage_tasks`（有则展示）
- **静态 Workflow / ReAct / spawn** 回归不得因本 plan 变红
- 改 `docs/nacos/*.yaml` → `python scripts/sync_nacos.py` + `python scripts/start.py --restart orchestrator`
- 单测：`mvn test -pl orchestrator -Dtest='*Harness*,*PlanNotebook*,*TaskBoard*' -q`
- Live：`python scripts/verify_planner_executor_live.py`（可 `--suite p1,p3,...`）

---

## 代码对照缺口（2026-08-13 冻结）

| # | Spec | 代码实况 | H-7 处置 |
|---|------|----------|----------|
| G1 | §4.2 / H-6 follow-up：一级 TaskBoard ← H1 `tasks` SSE | Loop 只发 `plan`/`worker-*`，**无** `phase=tasks`；前端 `resolveTaskBoardPrimaryItems` 等空 | **本 plan Task 1–2** |
| G2 | §4.1 handoff 行 | `ProcessingStep.done(worker, …, detail=status)`，detail 常为 `done`/`fail`，**非** handoff 摘要 | **Task 3** |
| G3 | §4.3 `planner-answer` | `synthesizeAnswer` 直接透传 `agentRuntime` token，**无**显式 answer 步 | **Task 4** |
| G4 | §5.4 ③ 目标变更 | `PlannerHarnessExecutor` load notebook 后**不**比对 follow-up / 不标 `obsolete` | **Task 5** |
| G5 | D7 `plan.worker_*` 审计 | harness **未**调 `PlanExecutionAuditService` | **Task 6**（薄） |
| G6 | §9.2 P1–P8 Live | 仅有 `verify_planner_harness_kernel_smoke.py` + routing v6 smoke | **Task 7** |
| G7 | §5.2 H1 LLM 折叠 | `renderForPlanner` 确定性 `[folded] N oldest` | **延期**（P6 soft 认截断标记） |
| G8 | 阶段 D / R-4 | `PlanWorkflow*` 源码仍在 | **另开 plan**（H-7 后） |
| G9 | handoff 双写 L1 | Loop 直调 Worker → 只写 H1；Planner 内 `dispatch_worker` 天然 tool_result | **接受**：跨波靠 H1 注入；不另造 L1 append |
| G10 | 完整 4.7.7 Middleware | 仅 harness 薄 `GoalAlignmentValidator` | **延期** |

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../plan/harness/HarnessTaskBoardProjector.java` | H1 `TaskItem` → `TaskBoardItemView`（status 映射 + dependsOn） |
| `orchestrator/.../plan/harness/PlannerHarnessLoop.java` | 发/刷 `tasks` 步；worker done 带 handoff；调审计 |
| `orchestrator/.../plan/harness/PlannerHarnessExecutor.java` | follow-up goal / obsolete |
| `orchestrator/.../plan/harness/PlanNotebook.java` | `setOriginalGoal` 或等价可变目标 API（若 final 字段需重构） |
| `orchestrator/.../taskboard/TaskBoardItemView.java` | 可选 `dependsOn` |
| `orchestrator/.../agent/ProcessingStepSerde.java` | serde `dependsOn` |
| `orchestrator/.../processing/StepMetadata*.java` | 若需扩展 withTasks 签名则跟改 |
| `orchestrator/.../plan/PlanExecutionAuditService.java` | +`plan.worker_started` / `plan.worker_completed` / `plan.worker_failed` |
| `orchestrator/.../plan/harness/HarnessPlanner.java` | synthesize 前后发 `planner-answer` 步 |
| `scripts/verify_planner_executor_live.py` | P1–P8 |
| Tests under `orchestrator/src/test/.../plan/harness/` | 投影 / obsolete / loop SSE |
| `docs/superpowers/specs/2026-08-05-planner-executor-rebuild-design.md` | §7.0 H-7 完成后 ✅ |
| `CLAUDE.md` / `docs/implementation-plan.md` / `specs/README.md` | 进度行同步（Task 8） |

---

### Task 1: H1 → TaskBoard 投影器

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/plan/harness/HarnessTaskBoardProjector.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/taskboard/TaskBoardItemView.java`（加可选 `dependsOn`）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepSerde.java`（写出 `dependsOn`）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/plan/harness/HarnessTaskBoardProjectorTest.java`

**Interfaces:**
- Produces: `HarnessTaskBoardProjector.project(PlanNotebook) → List<TaskBoardItemView>`
- Status 映射（前端只认 `pending|in_progress|completed|cancelled`）：
  - `pending` → `pending`
  - `in_progress` → `in_progress`
  - `done` → `completed`
  - `fail` / `obsolete` → `cancelled`
- `content` = `TaskItem.label()`；`id` = `taskId`；`dependsOn` = 原列表（可空）

- [ ] **Step 1: 写失败测试**

```java
@Test
void mapsDoneAndFailAndDependsOn() {
    PlanNotebook nb = PlanNotebook.create("g", "q", "chat", 12, 24);
    nb.getTaskQueue().add(new TaskItem("t1", "调研", "done", List.of(), null, null, null));
    nb.getTaskQueue().add(new TaskItem("t2", "分析", "pending", List.of("t1"), null, null, null));
    nb.getTaskQueue().add(new TaskItem("t3", "废", "obsolete", List.of(), null, null, null));
    List<TaskBoardItemView> views = HarnessTaskBoardProjector.project(nb);
    assertThat(views).extracting(TaskBoardItemView::id, TaskBoardItemView::status)
            .containsExactly(
                    tuple("t1", "completed"),
                    tuple("t2", "pending"),
                    tuple("t3", "cancelled"));
    assertThat(views.get(1).dependsOn()).containsExactly("t1");
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `mvn test -pl orchestrator -Dtest=HarnessTaskBoardProjectorTest -q`  
Expected: FAIL（类不存在或 dependsOn 不存在）

- [ ] **Step 3: 实现投影 + 扩展 View**

```java
public record TaskBoardItemView(
        String id, String content, String status,
        List<String> dependsOn) {
    public TaskBoardItemView(String id, String content, String status) {
        this(id, content, status, null);
    }
}
```

`HarnessTaskBoardProjector.project` 按上表映射；`dependsOn` 空则传 `null`（serde 省略）。  
**注意**：全仓 `new TaskBoardItemView(id, content, status)` 须仍编译（三参构造保留）。

- [ ] **Step 4: Serde 写出 dependsOn**

在 `ProcessingStepSerde` 写 `tasks` 循环内：

```java
if (item.dependsOn() != null && !item.dependsOn().isEmpty()) {
    row.put("dependsOn", item.dependsOn());
}
```

- [ ] **Step 5: 跑测绿 + commit**

```bash
mvn test -pl orchestrator -Dtest=HarnessTaskBoardProjectorTest,ReactTaskBoardTest -q
git add orchestrator/src/main/java/com/sunshine/orchestrator/plan/harness/HarnessTaskBoardProjector.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/taskboard/TaskBoardItemView.java \
  orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepSerde.java \
  orchestrator/src/test/java/com/sunshine/orchestrator/plan/harness/HarnessTaskBoardProjectorTest.java
git commit -m "$(cat <<'EOF'
feat(harness): project H1 taskQueue to TaskBoard views

EOF
)"
```

---

### Task 2: Loop 下发 / 刷新 `tasks` SSE

**Files:**
- Modify: `orchestrator/.../plan/harness/PlannerHarnessLoop.java`
- Test: `orchestrator/.../plan/harness/PlannerHarnessLoopTest.java`（扩展）

**Interfaces:**
- Consumes: `HarnessTaskBoardProjector.project`
- Produces: 流中出现 `ProcessingStep`：`id=tasks` / `phase=tasks`，`metadata=StepMetadata.withTasks(items, revision, progress)`
- 刷新时机：每次 `planNext` 成功且校验通过后；每个 worker 状态变更（running→done/fail）后
- `revision`：notebook 内递增整型即可（可放 `PlanNotebook` 新字段 `taskBoardRevision`，或 Loop 本地 `AtomicInteger`）；前端 `hasRealTaskBoardItems`：若走 `metadata.tasks` 需 `taskRevision≥1`，**故 revision 从 1 起**

- [ ] **Step 1: 写失败测试**

```java
@Test
void emitsTasksStepAfterPlanWithMetadata() {
    // 既有 Loop 单测夹具：stub planner.planNext 写入 1 个 pending task，worker 立即成功
    List<StreamToken> tokens = collect(loop.run(ctx, notebook));
    ProcessingStep tasks = findStep(tokens, "tasks");
    assertThat(tasks).isNotNull();
    assertThat(tasks.phase()).isEqualTo("tasks");
    assertThat(tasks.metadata().tasks()).isNotEmpty();
    assertThat(tasks.metadata().taskRevision()).isGreaterThanOrEqualTo(1);
}
```

- [ ] **Step 2: 跑测确认失败**

Run: `mvn test -pl orchestrator -Dtest=PlannerHarnessLoopTest#emitsTasksStepAfterPlanWithMetadata -q`  
Expected: FAIL

- [ ] **Step 3: Loop 内发射**

在 `PlannerHarnessLoop` 增加：

```java
private StreamToken tasksSnapshot(PlanNotebook nb, int revision) {
    List<TaskBoardItemView> items = HarnessTaskBoardProjector.project(nb);
    String progress = revision + ":" + items.size();
    StepMetadata meta = StepMetadata.withTasks(items, revision, progress);
    // 使用现有 ProcessingStep 工厂或 withMetadata 变体；若无，扩展 done/running 重载带 metadata
    return StreamToken.step(ProcessingStep.done("tasks", "tasks", "任务看板", null)
            /* 需带 metadata — 用项目已有 withMetadata / copy 构造 */);
}
```

查阅 `ProcessingStep` 是否已有 `withMetadata`；若无，加：

```java
public ProcessingStep withMetadata(StepMetadata metadata) {
    return new ProcessingStep(id, phase, lifecycle, summary, startedAt, endedAt, durationMs,
            detail, reasoning, output, result, ts, label, metadata, contentBlocks, subSteps, stepSummary);
}
```

`plan` done 后与每个 `worker` done 后 `emitted.add(tasksSnapshot(...))`。

- [ ] **Step 4: 跑测绿 + commit**

```bash
mvn test -pl orchestrator -Dtest=PlannerHarnessLoopTest -q
git commit -m "$(cat <<'EOF'
feat(harness): emit tasks SSE from H1 projection

EOF
)"
```

---

### Task 3: worker 步写入 handoff 摘要

**Files:**
- Modify: `PlannerHarnessLoop.java`（`executeTaskWithRetries` 返回 handoff 或从 notebook 最后 RoundRecord 读 summary）
- Modify: `WorkerDispatchTool.java`（若需暴露 last handoff；优先从 `NodeResult.summary` 读，少改 API）
- Test: `PlannerHarnessLoopTest` 增断言

**Interfaces:**
- `ProcessingStep.done(workerId, "worker", label, handoffText)` 且 `result`/`detail` = handoff 正文（前端 `resolveWorkerHandoffText`）
- 失败时 detail = 失败原因（已有 fail round summary）

- [ ] **Step 1: 写失败测试**

```java
@Test
void workerDoneStepCarriesHandoffNotBareStatus() {
    List<StreamToken> tokens = collect(loop.run(ctx, notebook));
    ProcessingStep w = findStep(tokens, "worker-t1");
    assertThat(w.detail()).isNotEqualTo("done");
    assertThat(w.detail()).containsIgnoringCase("摘要"); // stub handoff
}
```

- [ ] **Step 2: 实现**

`executeTaskWithRetries` 成功后从 notebook：

```java
String handoff = latestRoundSummary(notebook, taskId); // NodeResult.summary 或 RoundRecord.assessReason
emitted.add(StreamToken.step(ProcessingStep.done(workerStepId, "worker", ready.label(), handoff)));
```

禁止再把裸 `status` 当 detail。

- [ ] **Step 3: 跑测绿 + commit**

```bash
mvn test -pl orchestrator -Dtest=PlannerHarnessLoopTest -q
git commit -m "$(cat <<'EOF'
fix(harness): put worker handoff text on timeline step

EOF
)"
```

---

### Task 4: 显式 `planner-answer` 步

**Files:**
- Modify: `HarnessPlanner.java` `synthesizeAnswer`
- Test: `HarnessPlannerTest` 或 Loop 集成测

**Interfaces:**
- 综合回答前发 `running`：`id=planner-answer` / `phase=answer`（或 `phase=generate` 若前端已认 answer；H-6 用 `planner-answer` id）
- 流结束后发 `done`
- **禁止**对模型 token 做截断

- [ ] **Step 1: 写失败测试**

```java
@Test
void synthesizeEmitsPlannerAnswerStep() {
    Flux<StreamToken> flux = planner.synthesizeAnswer(notebook, ctx);
    assertThat(collect(flux).stream().map(this::stepId))
            .contains("planner-answer");
}
```

- [ ] **Step 2: 实现**

```java
public Flux<StreamToken> synthesizeAnswer(PlanNotebook notebook, ExecutionStreamContext ctx) {
    AgentRunRequest request = buildRequest(notebook, ctx, HINT_ANSWER);
    return Flux.defer(() -> {
        WorkerDispatchTool.DispatchSession session = bindDispatchSession(notebook, ctx, request.runId());
        StreamToken start = StreamToken.step(ProcessingStep.running(
                "planner-answer", "answer", "综合回答"));
        return Flux.just(start)
                .concatWith(agentRuntime.run(request))
                .concatWith(Flux.just(StreamToken.step(ProcessingStep.done(
                        "planner-answer", "answer", "综合回答", "完成"))))
                .doFinally(sig -> WorkerDispatchTool.clearSession(session));
    });
}
```

- [ ] **Step 3: 跑测绿 + commit**

```bash
mvn test -pl orchestrator -Dtest=HarnessPlannerTest,PlannerHarnessLoopTest -q
git commit -m "$(cat <<'EOF'
feat(harness): emit planner-answer timeline step

EOF
)"
```

---

### Task 5: Follow-up 目标变更 → obsolete + replan

**Files:**
- Modify: `PlanNotebook.java`（`originalGoal` 改为可 `@Setter` 或 `updateGoal(String)`）
- Modify: `PlannerHarnessExecutor.java`
- Test: `PlannerHarnessExecutorTest.java`

**Interfaces:**
- Consumes: `ctx.userContent()` / effective query
- 规则（S6 ③，可量化、不做二次 LLM）：
  1. load 到已有 notebook 且 `userQuery`/`originalGoal` 与本轮 query **规范化后不相等**
  2. → `notebook.updateGoal(newQuery)`；所有非 `done` 的 task → `obsolete`；`replanCount++`（或留给 Loop 首轮 planNext 自然重排）
  3. 已 `done` 保留（边界 1）
- 首轮 create 路径不变

- [ ] **Step 1: 写失败测试**

```java
@Test
void followUpQueryMarksPendingObsoleteAndUpdatesGoal() {
    PlanNotebook existing = PlanNotebook.create("旧目标", "旧目标", "chat", 12, 24);
    existing.setSessionId("c1");
    existing.getTaskQueue().add(new TaskItem("t1", "A", "done", List.of(), null, null, null));
    existing.getTaskQueue().add(new TaskItem("t2", "B", "pending", List.of(), null, null, null));
    when(store.load("c1")).thenReturn(Optional.of(existing));
    ExecutionStreamContext ctx = ctxWith("c1", "新目标：竞品改为 B 公司");
    executor.execute(ctx).blockLast();
    assertThat(existing.getOriginalGoal()).contains("新目标");
    assertThat(find(existing, "t1").status()).isEqualTo("done");
    assertThat(find(existing, "t2").status()).isEqualTo("obsolete");
}
```

- [ ] **Step 2: 实现 Executor 闸门**

```java
PlanNotebook notebook = store.load(sessionId).orElseGet(() -> createNotebook(ctx, sessionId));
applyFollowUpGoalChange(notebook, resolveQuery(ctx));
```

`applyFollowUpGoalChange`：equal → no-op；不等 → updateGoal + obsolete 非 done。

- [ ] **Step 3: 跑测绿 + commit**

```bash
mvn test -pl orchestrator -Dtest=PlannerHarnessExecutorTest -q
git commit -m "$(cat <<'EOF'
feat(harness): obsolete pending tasks on follow-up goal change

EOF
)"
```

---

### Task 6: 薄审计 `plan.worker_*`

**Files:**
- Modify: `PlanExecutionAuditService.java`
- Modify: `PlannerHarnessLoop.java`（或 `WorkerDispatchTool`）
- Test: `PlanExecutionAuditServiceTest`（若无则新建薄测）/ Loop 测 verify mock

**Interfaces:**
- `publishWorkerStarted(conv, msg, user, tenant, taskId, label)`
- `publishWorkerCompleted(..., taskId, summaryPreview)`（summary **不**二次加工，只限长日志字段若审计通道已有 max — 跟现有 `plan.node.attempt` 模式）
- `publishWorkerFailed(..., taskId, error)`
- eventType 字符串：`plan.worker_started` / `plan.worker_completed` / `plan.worker_failed`

- [ ] **Step 1: 按现有 `plan.node.attempt` 抄三个 publish 方法**
- [ ] **Step 2: Loop 在 worker running/done/fail 各调一次**（无 PlanId 时用 `sessionId`/`notebook` 占位 planId=`harness:{sessionId}`）
- [ ] **Step 3: 单测 + commit**

```bash
git commit -m "$(cat <<'EOF'
feat(harness): audit plan.worker_* events

EOF
)"
```

---

### Task 7: Live 脚本 `verify_planner_executor_live.py`

**Files:**
- Create: `scripts/verify_planner_executor_live.py`
- Modify: `CLAUDE.md` 运维表加一行（Task 8 一并改也可）

**Interfaces:**
- CLI：`python scripts/verify_planner_executor_live.py [--suite p1,p3,p4,...|all]`
- Env：`GATEWAY_URL`（默认 `http://ecs4c16g:8000`）、`REDIS_*`、`HARNESS_LIVE_TIMEOUT_SEC`（建议默认 600；P8 可单独更长）
- 请求体：`executionMode=pro`（兼容读 `executionPreference=pro`）；P4 用 `fast`；P3 用 `workflow` + `#knowledge-qa`（或现网静态 workflow 种子 id）
- 解析 SSE：复用其他 live 脚本的 curl + 抽 `step` 事件

| Suite | kind | 硬检查 |
|-------|:----:|--------|
| **P1** | chat | SSE 含 `plan`/`plan-R*`、`worker-*`、`tasks`（metadata.tasks 非空）、`planner-answer`；终态有正文；Redis 键 `sunshine:plan:notebook:{convId}` 存在 |
| **P2** | task | 同 P1 骨架；诱导 Worker 内 spawn（query 明确要求 spawn_subagent）；soft：出现 `subagent-*` 或日志含 spawn（无则 WARN 不 fail） |
| **P3** | / | `executionMode=workflow` + 已知 `#` workflow → 出现 planGraph/DAG 相关步或静态节点步；**不得**进 harness notebook |
| **P4** | / | `fast` 简单问答 → **无** `worker-*` harness 步；走 ReAct |
| **P5** | chat | 启动 pro 长任务 → 记 notebook JSON → `python scripts/start.py --restart orchestrator` → 同会话 follow-up「继续」→ notebook `in_progress` 已被修复为 fail 或不阻塞；能继续出 `plan-R*` 或综合（hard：Redis load 成功且无 5xx） |
| **P6** | chat | soft：多波后 notebook / 注入日志含 `[folded]` 或 rounds≥near-keep；无则 WARN |
| **P7** | task | query 故意信息不足 → 首轮可仅调研 worker；随后出现 `plan-R2` 或 replan；若 SSE 有二级 `manage_tasks` 则 tasks.secondary 可观测（soft） |
| **P8** | task | soft/可选：`--suite p8` 默认 skip；文档注明需长墙钟；检查 Nacos `worker.timeout-ms≥3600000` 配置存在即 PASS（真跑 spawn+exec 600s 作 `--full-p8`） |

- [ ] **Step 1: 脚手架**（auth / create conv / chat_sse / parse_steps），抄 `verify_react_taskboard_live.py`

- [ ] **Step 2: 实现 P1 + P3 + P4（最短回归三角）并本地跑通**

```bash
python scripts/verify_planner_executor_live.py --suite p1,p3,p4
```

Expected: 三绿（环境全链路 up + harness.enabled=true）

- [ ] **Step 3: 补 P2/P5/P6/P7；P8 默认配置门**

- [ ] **Step 4: commit**

```bash
git add scripts/verify_planner_executor_live.py
git commit -m "$(cat <<'EOF'
test: add planner-executor live verifier P1–P8

EOF
)"
```

---

### Task 8: 文档进度同步（H-7 完成后）

**Files:**
- Modify: `docs/superpowers/specs/2026-08-05-planner-executor-rebuild-design.md`（§7.0 H-7 ✅；状态行 v12）
- Modify: `docs/superpowers/specs/README.md` 活跃表
- Modify: `docs/implementation-plan.md` 4.14 行
- Modify: `CLAUDE.md` 进度行 + 脚本表
- Modify: 本 plan 头 `状态：✅`

- [ ] **Step 1: 仅在 Task 7 绿且 G1–G6 代码合入后改文档**（禁止提前把 H-7 标 ✅）
- [ ] **Step 2: 明确下一波 = **阶段 D / R-4**（另开 `docs/superpowers/plans/2026-08-XX-planner-stage-d-r4.md`）
- [ ] **Step 3: commit**

```bash
git commit -m "$(cat <<'EOF'
docs: mark planner-executor H-7 live complete

EOF
)"
```

---

## Out of scope（后续 plan）

| 项 | 去向 |
|----|------|
| 删 `PlanWorkflow*` / Approval / Catalog `plan-workflow.*` / 前端 Approval 文件 | **阶段 D / R-4** |
| H1 LLM 折叠替换确定性截断 | 压缩增强 / 五层 follow-up |
| 完整 GoalAlignment Middleware（4.7.7） | react-goal-alignment |
| Decision D12 Planner | 独立 D12 plan |
| `intent.classifier` live bump | routing 延期项 |

---

## Spec coverage（self-review）

| Spec 要求 | Task |
|-----------|------|
| §4.2 一级 TaskBoard ← H1 | T1 T2 |
| §4.1 handoff 仅时间线 | T3 |
| §4.3 planner-answer | T4 |
| §5.4 ③ 目标变更 | T5 |
| D7 plan.worker_* | T6 |
| §9.2 P1–P8 | T7 |
| §7.0 进度 / 文档 | T8 |
| 阶段 D | **明确不做** |
| H1 LLM 折叠 / 4.7.7 | **明确不做** |

## 风险与默认假设

1. **Live 不稳定**：P1/P7 依赖 LLM 是否吐调研步 — 硬检查以 SSE 契约（plan/worker/tasks/answer）为主，语义用 soft WARN。  
2. **P5 杀进程**：只 `start.py --restart orchestrator`，勿动 Redis。  
3. **二次投影**：tasks 步可多次 `done` 刷新；前端 merge 引擎须接受同 id 覆盖（既有 ReAct tasks 行为）。  
4. **阶段 D 仍禁**：H-7 绿也不删 PlanWorkflow，避免与静态 Workflow 回归纠缠。
