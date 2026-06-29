# Processing Timeline V2 Implementation Plan

> **Superseded（2026-06-29）**：`ProcessingTimeline.vue` 已删除；实现态见 `OperationStack.vue` + `processingSteps.ts` / `processingStepsPause.ts`。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Refactor backend processing events into an extensible event-stream + aggregator architecture, exposing per-step before/active/after summaries with duration timeline; upgrade frontend `ProcessingTimeline` to render V2 fields with V1 fallback.

**Architecture:** Contributors emit `ProcessingEvent` into `ProcessingTimelineSession`; `TimelineAggregator` maintains step state machine and computes `startedAt/endedAt/durationMs`; snapshots serialize to existing SSE `type:step` + MySQL `steps` column. Frontend parses V2 `summary/lifecycle` with V1 migration helper.

**Tech Stack:** Java 21 / Spring Boot 3.2 Orchestrator, AgentScope Hook, Redis Generation stream, Vue 3 + TypeScript, Playwright E2E

**Spec:** `docs/superpowers/specs/2026-06-13-processing-timeline-v2-design.md`

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `orchestrator/.../processing/EventKind.java` | Event type enum |
| Create | `orchestrator/.../processing/ProcessingEvent.java` | Immutable event record |
| Create | `orchestrator/.../processing/StepSummary.java` | before/active/after record |
| Create | `orchestrator/.../processing/TimelineAggregator.java` | State machine + merge |
| Create | `orchestrator/.../processing/ProcessingTimelineSession.java` | Public session API |
| Create | `orchestrator/.../processing/StepLabels.java` | Default copy templates |
| Create | `orchestrator/.../processing/ProcessingStepEmitter.java` | Snapshot → StreamToken |
| Modify | `orchestrator/.../agent/ProcessingStep.java` | Add V2 fields + compat |
| Modify | `orchestrator/.../agent/ProcessingStepMerger.java` | Merge preserving startedAt |
| Modify | `orchestrator/.../agent/StepEventBridge.java` | Bind Session not Sink |
| Modify | `orchestrator/.../agent/ProcessingStepHook.java` | Use Session API |
| Modify | `orchestrator/.../agent/AgentScopeEventMapper.java` | Use Session via bridge |
| Modify | `orchestrator/.../controller/ChatController.java` | Intent/Generate via Session |
| Modify | `orchestrator/.../generation/GenerationJob.java` | Wire Session emitter |
| Modify | `orchestrator/.../conversation/GenerationFlushScheduler.java` | Serialize V2 step JSON |
| Create | `orchestrator/.../processing/TimelineAggregatorTest.java` | Unit tests |
| Modify | `sunshine-ui/src/api/processingSteps.ts` | V2 types + migrateV1Step |
| Modify | `sunshine-ui/src/components/ProcessingTimeline.vue` | Timeline UI |
| Modify | `sunshine-ui/mock-server.mjs` | V2 step payloads |
| Modify | `sunshine-ui/e2e/processing-timeline.spec.ts` | Assert durations/summary |

---

### Task 1: Event model + Aggregator core

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/EventKind.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/ProcessingEvent.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/StepSummary.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/TimelineAggregator.java`
- Create: `orchestrator/src/test/java/com/sunshine/orchestrator/processing/TimelineAggregatorTest.java`

- [x] **Step 1: Write failing aggregator test**

```java
// TimelineAggregatorTest.java
@Test
void pendingStartComplete_producesThreePhaseSummaryAndDuration() {
    TimelineAggregator agg = new TimelineAggregator();
    agg.apply(new ProcessingEvent("intent", "intent", EventKind.PENDING, "准备识别意图", 1000L, null));
    agg.apply(new ProcessingEvent("intent", "intent", EventKind.START, "正在分析用户输入", 1100L, null));
    agg.apply(new ProcessingEvent("intent", "intent", EventKind.COMPLETE, "判定为：知识库查询", 1250L, "知识库查询"));

    ProcessingStep step = agg.snapshot().get(0);
    assertThat(step.lifecycle()).isEqualTo("done");
    assertThat(step.summary().before()).isEqualTo("准备识别意图");
    assertThat(step.summary().active()).isEqualTo("正在分析用户输入");
    assertThat(step.summary().after()).isEqualTo("判定为：知识库查询");
    assertThat(step.startedAt()).isEqualTo(1100L);
    assertThat(step.endedAt()).isEqualTo(1250L);
    assertThat(step.durationMs()).isEqualTo(150L);
    assertThat(step.status()).isEqualTo("done");
    assertThat(step.label()).isEqualTo("识别意图");
}
```

- [x] **Step 2: Run test — expect FAIL**

```bash
mvn test -pl orchestrator "-Dtest=TimelineAggregatorTest" -q
```

- [x] **Step 3: Implement EventKind, ProcessingEvent, StepSummary, TimelineAggregator**

`TimelineAggregator` 要点：
- `LinkedHashMap<String, MutableStep>` 按 stepId 保序
- `label` 来自 `StepLabels.labelFor(stepId)` 或在 START 时从事件上下文传入
- COMPLETE/FAIL 时 `durationMs = endedAt - startedAt`（startedAt 缺失则用 ts）
- 输出 `ProcessingStep` record（先扩展 `agent/ProcessingStep.java`）

- [x] **Step 4: Run test — expect PASS**

- [x] **Step 5: Add progress + fail + skip tests and implement**

```java
@Test
void progress_overwritesActiveSummary() { /* PROGRESS 两次，active 为最后一次 */ }

@Test
void replayPreservesEarliestStartedAt() { /* 重放 START 不覆盖更早 startedAt */ }
```

---

### Task 2: Extend ProcessingStep record (V2 + compat)

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStep.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMerger.java`

- [x] **Step 1: Extend ProcessingStep**

```java
public record ProcessingStep(
    String id, String phase, String lifecycle,
    StepSummary summary,
    Long startedAt, Long endedAt, Long durationMs,
    String detail, long ts,
    String status, String label   // compat
) {
    // 保留 V1 工厂方法，内部填 lifecycle/summary 最小集
    public static ProcessingStep running(String id, String phase, String label) {
        return new ProcessingStep(id, phase, "running",
            new StepSummary(null, label, null),
            null, null, null, null, System.currentTimeMillis(),
            "running", label);
    }
    // done/error 同理
}
```

- [x] **Step 2: Update ProcessingStepMerger.upsert**

合并规则：同 id 时 `startedAt` 取非 null 更小值；`summary` 字段按非 null 覆盖合并。

- [x] **Step 3: Run existing tests**

```bash
mvn test -pl orchestrator "-Dtest=GenerationJobTest,StreamTokenCoalescerTest" -q
```

修复因 `ProcessingStep` 构造器变更导致的编译错误。

---

### Task 3: ProcessingTimelineSession + StepLabels

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/StepLabels.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/ProcessingTimelineSession.java`
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/ProcessingStepEmitter.java`

- [x] **Step 1: Implement StepLabels**

```java
public final class StepLabels {
    public static String labelFor(String stepId) { /* intent→识别意图, rag→检索知识库, ... */ }
    public static String beforeFor(String stepId) { /* 准备识别意图, ... */ }
    public static String activeFor(String stepId) { /* 正在分析用户输入, ... */ }
    public static String afterTemplate(String stepId) { /* 判定为：%s, ... */ }
}
```

- [x] **Step 2: Implement Session**

```java
public final class ProcessingTimelineSession {
    private final TimelineAggregator aggregator = new TimelineAggregator();
    private Consumer<ProcessingStep> onStepChanged = s -> {};

    public void onStepChanged(Consumer<ProcessingStep> listener) { this.onStepChanged = listener; }

    public void pending(String stepId, String phase) {
        apply(stepId, phase, EventKind.PENDING, StepLabels.beforeFor(stepId), null);
    }
    public void start(String stepId, String phase) {
        apply(stepId, phase, EventKind.START, StepLabels.activeFor(stepId), null);
    }
    public void complete(String stepId, String detail) {
        String after = formatAfter(stepId, detail);
        apply(stepId, null, EventKind.COMPLETE, after, detail);
    }
    // fail/skip/progress 同理

    private void apply(...) {
        ProcessingStep prev = aggregator.get(stepId).orElse(null);
        aggregator.apply(event);
        ProcessingStep next = aggregator.get(stepId).orElseThrow();
        if (!next.equals(prev)) onStepChanged.accept(next);
    }
}
```

- [x] **Step 3: ProcessingStepEmitter**

```java
public final class ProcessingStepEmitter {
    public static StreamToken toToken(ProcessingStep step) {
        return StreamToken.step(step);
    }
}
```

- [x] **Step 4: Unit test Session emits on change only**

---

### Task 4: Wire Session into GenerationJob + ChatController

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJob.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/conversation/GenerationFlushScheduler.java`

- [x] **Step 1: GenerationJob holds ProcessingTimelineSession**

```java
private final ProcessingTimelineSession timelineSession = new ProcessingTimelineSession();

// 在 start() 开头：
timelineSession.onStepChanged(step -> {
    ProcessingStepMerger.upsert(stepsBuffer, step);
    streamService.appendChunk(generationId, nextSeq++, flushScheduler.metaStep(step));
});
```

- [x] **Step 2: ChatController intent 路径改用 session**

替换：
```java
ProcessingStep.running("intent", ...)
```
为：
```java
timelineSession.pending("intent", "intent");
timelineSession.start("intent", "intent");
// classify 完成后：
timelineSession.complete("intent", detail);
```

`wrapStream` 路径同样创建 session 并接入 `stepsBuffer`。

- [x] **Step 3: metaStep 序列化 V2 全字段**

`GenerationFlushScheduler.metaStep` 用 Jackson 输出 `lifecycle/summary/startedAt/.../status/label`。

- [x] **Step 4: Run GenerationJobTest + manual curl**

```bash
mvn test -pl orchestrator "-Dtest=GenerationJobTest" -q
```

更新 `start_persistsStepsOnComplete` 断言 V2 字段。

---

### Task 5: Migrate Agent path (Hook + EventMapper + Bridge)

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridge.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepHook.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentScopeEventMapper.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/SunshineAgent.java`

- [x] **Step 1: StepEventBridge binds ProcessingTimelineSession**

```java
private static final ThreadLocal<ProcessingTimelineSession> SESSION = new ThreadLocal<>();

public static void bind(ProcessingTimelineSession session) { SESSION.set(session); }
public static void emit(Consumer<ProcessingTimelineSession> action) {
    ProcessingTimelineSession s = SESSION.get();
    if (s != null) action.accept(s);
}
```

- [x] **Step 2: ProcessingStepHook**

```java
// PreActing search_knowledge:
StepEventBridge.emit(s -> { s.pending("rag","rag"); s.start("rag","rag"); });
// PostActing:
StepEventBridge.emit(s -> s.complete("rag", detail));
// 其他 tool:
StepEventBridge.emit(s -> { s.pending("tool-"+name,"agent"); s.start(...); });
```

- [x] **Step 3: AgentScopeEventMapper**

REASONING 首次 → `session.start("agent","agent")`  
AGENT_RESULT last → `session.complete("agent", null)` + generate complete  
HINT search_knowledge → 若 Hook 未覆盖则 fallback `session.start("rag","rag")`

- [x] **Step 4: SunshineAgent binds session before agent.stream()**

```java
ProcessingTimelineSession session = new ProcessingTimelineSession();
StepEventBridge.bind(session);
// session.onStepChanged → stepSink.tryEmitNext 改为直接 emit ProcessingStep
```

- [x] **Step 5: Integration smoke**

```bash
mvn compile -pl orchestrator -am -q
# 重启 orchestrator，knowledge 路径对话，检查 SSE step 含 summary 三字段
```

---

### Task 6: Frontend V2 types + timeline UI

**Files:**
- Modify: `sunshine-ui/src/api/processingSteps.ts`
- Modify: `sunshine-ui/src/api/conversations.ts`
- Modify: `sunshine-ui/src/components/ProcessingTimeline.vue`

- [x] **Step 1: Extend types + normalizeStep + migrateV1Step**

```typescript
export function migrateV1Step(step: ProcessingStep): ProcessingStep {
  if (step.summary) return step
  const lifecycle = step.lifecycle ?? step.status ?? 'running'
  return {
    ...step,
    lifecycle,
    summary: {
      before: lifecycle === 'pending' ? step.label : undefined,
      active: lifecycle === 'running' ? step.label : undefined,
      after: lifecycle === 'done' ? (step.detail ?? step.label) : undefined,
    },
  }
}

export function formatDuration(ms?: number): string {
  if (!ms) return ''
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`
}

export function totalDuration(steps: ProcessingStep[]): number {
  return steps.filter(s => s.durationMs).reduce((sum, s) => sum + (s.durationMs ?? 0), 0)
}
```

- [x] **Step 2: Upgrade ProcessingTimeline.vue**

- 标题行右侧：`总耗时 {{ formatDuration(totalDuration(steps)) }}`
- 每行：左侧轴点 + 竖线；右侧 `durationMs`
- body 内三行摘要：`summary.before` / `summary.active` / `summary.after`（带图标 ✓ / …）
- 无 summary 时 fallback V1 label/detail

- [x] **Step 3: parseSteps in conversations.ts 调用 migrateV1Step**

---

### Task 7: mock-server + E2E

**Files:**
- Modify: `sunshine-ui/mock-server.mjs`
- Modify: `sunshine-ui/e2e/processing-timeline.spec.ts`
- Modify: `sunshine-ui/e2e/processing-timeline-real.spec.ts`

- [x] **Step 1: mock-server stepPayload V2**

```javascript
function stepPayload(id, phase, lifecycle, label, opts = {}) {
  return JSON.stringify({
    type: 'step', id, phase, lifecycle,
    summary: opts.summary,
    startedAt: opts.startedAt,
    endedAt: opts.endedAt,
    durationMs: opts.durationMs,
    detail: opts.detail ?? null,
    ts: Date.now(),
    status: lifecycle, label,
  })
}
```

- [x] **Step 2: E2E assert 三态文案 + 耗时**

```typescript
await expect(timeline.getByText('准备识别意图')).toBeVisible()
await expect(timeline.getByText('正在分析用户输入')).toBeVisible()
await expect(timeline.getByText(/判定为/)).toBeVisible()
await expect(timeline.getByText(/\d+ms|\d+\.\d+s/)).toBeVisible()
```

- [x] **Step 3: Run E2E**

```bash
cd sunshine-ui
npx playwright test e2e/processing-timeline.spec.ts
# 真实后端（BFF+Orchestrator 已启）:
npx playwright test e2e/processing-timeline-real.spec.ts
```

---

## Spec Coverage Check

| Spec requirement | Task |
|------------------|------|
| EventKind + ProcessingEvent | Task 1 |
| TimelineAggregator state machine | Task 1 |
| Session API | Task 3 |
| Contributor migration | Task 4, 5 |
| SSE V2 + compat status/label | Task 2, 4 |
| MySQL steps JSON upgrade | Task 2, 4 (no Flyway) |
| Frontend三态 + 耗时 | Task 6 |
| V1 降级 | Task 6 |
| E2E | Task 7 |

## Execution Order

```
Task 1 → Task 2 → Task 3 → Task 4 → Task 5 → Task 6 → Task 7
```

Tasks 6 可与 Task 5 并行（前端/mock），但依赖 Task 2 的 payload 结构定义。
