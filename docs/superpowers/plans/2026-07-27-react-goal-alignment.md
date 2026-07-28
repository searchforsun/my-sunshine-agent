# ReAct 目标对齐与失败预算 Hook（4.7.7）Implementation Plan

> **状态**：📋 待执行
> **Spec**：[2026-07-27-react-goal-alignment-design.md](../specs/2026-07-27-react-goal-alignment-design.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 ReAct 模式增加 L2 目标对齐（周期性把原始问题+任务进度瞬态注入 reasoning 输入）与 L3 失败预算（工具连续失败达阈值注入强提示 + tool 步换文案），引擎级兜底目标漂移与同参数死循环。

**Architecture:** 两个独立 Middleware（`GoalAlignmentMiddleware` / `FailureBudgetMiddleware`）复用 AgentScope 原生 `TaskReminderMiddleware` 的瞬态 `<system-reminder>` 注入模式（不落 AgentState、不参与 compaction、`METADATA_SYNTHETIC` 标记）；per-run 状态收进新建 `AgentRunState`，挂 `StepEventBridgeRegistry` 与 bridge 同生命周期（对齐 P2-1 E5「Middleware 无状态」铁律）；提醒模板 SSOT = prompt-manager Catalog，开关/阈值 SSOT = Nacos `agent.execution.react.*`。

**Tech Stack:** Java 17 / Spring Boot · AgentScope 2.0（`MiddlewareBase.onReasoning/onActing`、`ToolResultEndEvent.state`）· JUnit5 + Mockito · Nacos · prompt-manager Catalog

---

## Global Constraints

- **Middleware 无状态**（P2-1 E5）：per-run 状态一律经 `bridgeIdOf(ctx)` → `StepEventBridge` 读写，禁止实例字段
- **瞬态注入**：提醒消息只追加到 `ReasoningInput.messages` 副本，禁止写 `AgentState.context`；必须带 `Msg.METADATA_SYNTHETIC=true` + `Msg.METADATA_REMINDER_KIND`
- **失败判定**：只用 `ToolResultEndEvent.getState()`（`ERROR`/`INTERRUPTED`），禁止正文关键字猜测
- **软提示非硬拒**：预算触发只注入提示，禁止拦截/拒绝工具调用（硬拒仅属 4.5.7 用户取消路径）
- **提示词 SSOT**：模板正文只进 prompt-manager Catalog（`/prompts`），禁止 Java/Nacos 硬编码正文；Nacos 仅放开关/阈值/时间线文案
- **MAIN-only**：SUB / PLANNER / 专家侧不注入
- **排除元工具**：`todo_write`（`TodoTasksBridge.isTodoWrite`）、`spawn_subagent`（`SpawnSubagentTool.NAME`）不计失败预算
- **不新增 Timeline phase / 前端组件**：budget 文案复用现有 tool 步 after
- 业务代码禁止多余空行；适量中文注释
- 编译命令：`mvn compile -pl orchestrator -am -q`；改 `docs/nacos/*.yaml` 后必跑 `python scripts/sync_nacos.py`

---

## File map

| 文件 | 职责 |
|------|------|
| `orchestrator/.../agent/AgentRunState.java`（新建） | per-run 可变状态：reasoning 轮次、goal-check 闸门、失败计数 Map、已触发 key 集 |
| `orchestrator/.../agent/StepEventBridgeRegistry.java`（修改） | bridge 绑定时创建/回收 `AgentRunState`；`runState(bridgeId)` 访问口 |
| `orchestrator/.../agent/StepEventBridge.java`（修改） | 静态门面 `runState(bridgeId)` / `userQuery(bridgeId)` 委托 |
| `orchestrator/.../config/AgentExecutionProperties.java`（修改） | `React` 下新增 `GoalCheck` / `ToolFailureBudget` 配置组 |
| `orchestrator/.../agent/FailureBudgetMiddleware.java`（新建） | `onActing` 统计失败 → 达阈值向 ReasoningInput 副本追加 budget 强提示 |
| `orchestrator/.../agent/GoalAlignmentMiddleware.java`（新建） | `onActing` 记 tool 完成闸门 + `onReasoning` 轮次计数 → 周期注入 goal-check |
| `orchestrator/.../agent/ProcessingStepMiddleware.java`（修改） | budget 触发时 tool 步 after 换「连续失败，需调整方案」 |
| `orchestrator/.../agent/ProcessingStepMiddlewareFactory.java`（修改） | 工厂产出 4 个 middleware 的共享链（新 `sharedChain()`），注入 `PromptCatalogHolder` |
| `orchestrator/.../agent/ReActAgentFactory.java`（修改） | `.middleware(...)` 改消费 `sharedChain()`（顺序：Processing → FailureBudget → GoalAlignment） |
| `docs/nacos/sunshine-orchestrator.yaml`（修改） | `goal-check.*` / `tool-failure-budget.*` 开关阈值 + `timeline.steps.tool-failure-budget.after` |
| prompt-manager DB（`/prompts` UI） | Catalog `react.goal-check` / `react.tool-failure-budget` 两条模板 |
| `scripts/verify_goal_alignment_live.py`（新建） | Live 验收 G1–G4 |

---

### Task 1: AgentRunState + StepEventBridge 状态载体 + Nacos 配置组

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentRunState.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridgeRegistry.java`（bind/clear 处）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridge.java`（静态门面尾部）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java:20-40`（`React` 内）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/AgentRunStateTest.java`

**Interfaces:**
- Produces（后续所有 Task 依赖）:
  - `AgentRunState`：`int nextReasoningIteration()`（1 起递增）、`int currentReasoningIteration()`、`int noteToolCompleted()`（返回累计业务 tool 完成数）、`int toolCompletedCount()`、`int recordFailure(String key)`（返回该 key 连续失败数）、`void resetFailure(String toolName)`（清 toolName 及其所有 sig key）、`boolean markBudgetTriggered(String key)`（首次返回 true）、`String toolNameOf(String toolUseId)` / `void bindToolName(String toolUseId, String toolName)` / `String unbindToolName(String toolUseId)`、`String lastErrorExcerpt(String toolName)` / `void recordErrorExcerpt(String toolName, String excerpt)`
  - `StepEventBridge.runState(String bridgeId)` → `AgentRunState`（未绑定返回 `null`）
  - `AgentExecutionProperties.React.GoalCheck`：`boolean enabled`（默认 false）、`int everyNThink`（默认 3）
  - `AgentExecutionProperties.React.ToolFailureBudget`：`boolean enabled`（默认 false）、`int sameSignatureMax`（默认 2）、`int perToolMax`（默认 3）
  - 失败 key 约定：`"tool:" + toolName`、`"sig:" + toolName + ":" + sha1hex`

- [ ] **Step 1: 写失败测试**

`AgentRunStateTest` 核心用例（纯 POJO，不需 Spring/Mockito）：

```java
class AgentRunStateTest {

    @Test
    void reasoningIterationIncrementsFromOne() {
        AgentRunState s = new AgentRunState();
        assertThat(s.nextReasoningIteration()).isEqualTo(1);
        assertThat(s.nextReasoningIteration()).isEqualTo(2);
        assertThat(s.currentReasoningIteration()).isEqualTo(2);
    }

    @Test
    void recordFailureCountsPerKeyAndResetClearsToolAndSignatures() {
        AgentRunState s = new AgentRunState();
        assertThat(s.recordFailure("tool:finance__query")).isEqualTo(1);
        assertThat(s.recordFailure("sig:finance__query:aaa")).isEqualTo(1);
        assertThat(s.recordFailure("sig:finance__query:bbb")).isEqualTo(1);
        s.resetFailure("finance__query");
        assertThat(s.recordFailure("tool:finance__query")).isEqualTo(1); // 重新从 1 起
        assertThat(s.recordFailure("sig:finance__query:aaa")).isEqualTo(1);
        assertThat(s.recordFailure("sig:finance__query:bbb")).isEqualTo(1);
    }

    @Test
    void markBudgetTriggeredOnlyFirstTime() {
        AgentRunState s = new AgentRunState();
        assertThat(s.markBudgetTriggered("tool:t1")).isTrue();
        assertThat(s.markBudgetTriggered("tool:t1")).isFalse();
    }

    @Test
    void toolUseIdNameMappingLifecycle() {
        AgentRunState s = new AgentRunState();
        s.bindToolName("tu-1", "finance__query");
        assertThat(s.toolNameOf("tu-1")).isEqualTo("finance__query");
        assertThat(s.unbindToolName("tu-1")).isEqualTo("finance__query");
        assertThat(s.toolNameOf("tu-1")).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl orchestrator -Dtest=AgentRunStateTest -q`
Expected: 编译错误（`AgentRunState` 不存在）

- [ ] **Step 3: 实现 AgentRunState**

```java
package com.sunshine.orchestrator.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单次 ReAct run 的可变状态载体（goal-check 闸门 + 工具失败预算）。
 * 与 bridge 同生命周期：StepEventBridgeRegistry.bind 创建、clear 回收；
 * Middleware 保持无状态（P2-1 E5），一律经 bridgeId 读写本对象。
 */
public class AgentRunState {

    private final AtomicInteger reasoningIteration = new AtomicInteger();
    private final AtomicInteger toolCompletedCount = new AtomicInteger();
    /** key=tool:{name} / sig:{name}:{sha1} → 连续失败次数（成功清零） */
    private final Map<String, AtomicInteger> failureCounts = new ConcurrentHashMap<>();
    /** 已触发过强提示的 key（每 run 每 key 只提示一次） */
    private final Map<String, Boolean> budgetTriggered = new ConcurrentHashMap<>();
    /** toolUseId → toolName（onActing 入口登记，End 事件回查） */
    private final Map<String, String> toolNameByUseId = new ConcurrentHashMap<>();
    /** toolName → 最近一次错误摘要（预算提示占位符 {lastError}） */
    private final Map<String, String> lastErrorByTool = new ConcurrentHashMap<>();

    public int nextReasoningIteration() {
        return reasoningIteration.incrementAndGet();
    }

    public int currentReasoningIteration() {
        return reasoningIteration.get();
    }

    public int noteToolCompleted() {
        return toolCompletedCount.incrementAndGet();
    }

    public int toolCompletedCount() {
        return toolCompletedCount.get();
    }

    public int recordFailure(String key) {
        return failureCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    public int failureCount(String key) {
        AtomicInteger c = failureCounts.get(key);
        return c != null ? c.get() : 0;
    }

    /** 工具成功：清该工具两个维度的连续失败计数 */
    public void resetFailure(String toolName) {
        failureCounts.remove("tool:" + toolName);
        failureCounts.keySet().removeIf(k -> k.startsWith("sig:" + toolName + ":"));
    }

    /** 首次触发返回 true；后续同 key 返回 false（防轰炸） */
    public boolean markBudgetTriggered(String key) {
        return budgetTriggered.putIfAbsent(key, Boolean.TRUE) == null;
    }

    public void bindToolName(String toolUseId, String toolName) {
        if (toolUseId != null && toolName != null) {
            toolNameByUseId.put(toolUseId, toolName);
        }
    }

    public String toolNameOf(String toolUseId) {
        return toolUseId != null ? toolNameByUseId.get(toolUseId) : null;
    }

    public String unbindToolName(String toolUseId) {
        return toolUseId != null ? toolNameByUseId.remove(toolUseId) : null;
    }

    public void recordErrorExcerpt(String toolName, String excerpt) {
        if (toolName != null && excerpt != null && !excerpt.isBlank()) {
            lastErrorByTool.put(toolName, excerpt.length() > 200 ? excerpt.substring(0, 200) : excerpt);
        }
    }

    public String lastErrorExcerpt(String toolName) {
        return toolName != null ? lastErrorByTool.getOrDefault(toolName, "") : "";
    }
}
```

- [ ] **Step 4: StepEventBridgeRegistry 挂载**

`StepEventBridgeRegistry` 的 bridge 绑定结构（`bind(messageId, session, queue)` 落点处）增加：

```java
private final Map<String, AgentRunState> runStateByBridge = new ConcurrentHashMap<>();

// bind(...) 系列方法内（与 hookTokenQueue 同一挂载点）：
runStateByBridge.put(messageId, new AgentRunState());

// clear(messageId) 内：
runStateByBridge.remove(messageId);

public AgentRunState runState(String bridgeId) {
    return bridgeId != null ? runStateByBridge.get(bridgeId) : null;
}
```

`StepEventBridge` 静态门面追加：

```java
/** per-run 可变状态（goal-check / 失败预算）；未绑定返回 null */
public static AgentRunState runState(String bridgeId) {
    return registry.runState(bridgeId);
}
```

注意：`userQuery(String messageId)` 门面已存在（`StepEventBridge.java:215`），goal-check 直接复用，**不新增**。

- [ ] **Step 5: AgentExecutionProperties 配置组**

`React` 内（`taskboard` / `subagent` 旁）追加：

```java
/** 4.7.7 L2 目标对齐 — SSOT：Nacos agent.execution.react.goal-check */
private GoalCheck goalCheck = new GoalCheck();
/** 4.7.7 L3 工具失败预算 — SSOT：Nacos agent.execution.react.tool-failure-budget */
private ToolFailureBudget toolFailureBudget = new ToolFailureBudget();

@Data
public static class GoalCheck {
    private boolean enabled = false;
    /** 每 N 轮 reasoning 注入一次目标对齐提醒 */
    private int everyNThink = 3;
}

@Data
public static class ToolFailureBudget {
    private boolean enabled = false;
    /** 同 toolName+参数指纹 连续失败上限 */
    private int sameSignatureMax = 2;
    /** 同 toolName 连续失败上限 */
    private int perToolMax = 3;
}
```

- [ ] **Step 6: 跑测试确认通过 + 编译**

Run: `mvn test -pl orchestrator -Dtest=AgentRunStateTest -q && mvn compile -pl orchestrator -am -q`
Expected: PASS + BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentRunState.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridgeRegistry.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/agent/StepEventBridge.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/agent/AgentRunStateTest.java
git commit -m "feat(orch): AgentRunState per-run carrier + goal-check/failure-budget config (4.7.7a)"
```

---

### Task 2: FailureBudgetMiddleware（L3 失败预算）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/FailureBudgetMiddleware.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/FailureBudgetMiddlewareTest.java`

**Interfaces:**
- Consumes: Task 1 的 `AgentRunState` 全部方法、`StepEventBridge.runState`、配置 `AgentExecutionProperties.React.ToolFailureBudget`；`PromptCatalogHolder.requireText(String id)`；`TodoTasksBridge.isTodoWrite(String)`；`SpawnSubagentTool.NAME`
- Produces: `FailureBudgetMiddleware implements MiddlewareBase`，构造签名 `FailureBudgetMiddleware(AgentExecutionProperties, PromptCatalogHolder)`；预算 key 规范方法 `static String signature(String toolName, Map<String,Object> input)`（输出 `toolName + ":" + sha1hex`，供 Task 3 复用）

- [ ] **Step 1: 写失败测试**

模式对齐 `ProcessingStepMiddlewareTest`（mock + `StepEventBridge.bind` + `ctxWithBridge`）：

```java
class FailureBudgetMiddlewareTest {

    private static final String BRIDGE = "test-bridge";
    private final AgentExecutionProperties props = mock(AgentExecutionProperties.class);
    private final PromptCatalogHolder catalog = mock(PromptCatalogHolder.class);
    private AgentExecutionProperties.React react;

    private RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u1").sessionId("m1")
                .put(ProcessingStepMiddleware.CTX_BRIDGE_ID, BRIDGE).build();
    }

    @BeforeEach
    void setUp() {
        react = new AgentExecutionProperties.React();
        react.getToolFailureBudget().setEnabled(true);
        react.getToolFailureBudget().setSameSignatureMax(2);
        react.getToolFailureBudget().setPerToolMax(3);
        when(props.getReact()).thenReturn(react);
        when(catalog.requireText("react.tool-failure-budget")).thenReturn(
                "【执行受阻】工具 {toolName} 已连续失败 {failCount} 次（最近错误：{lastError}）。禁止再用相同思路重试。");
        StepEventBridge.bind(BRIDGE, mock(ProcessingTimelineSession.class), new ConcurrentLinkedQueue<>());
    }

    @AfterEach
    void tearDown() {
        StepEventBridge.resetRegistry();
    }

    private ToolUseBlock toolUse(String id, String name, Map<String, Object> input) {
        ToolUseBlock tu = mock(ToolUseBlock.class);
        when(tu.getId()).thenReturn(id);
        when(tu.getName()).thenReturn(name);
        when(tu.getInput()).thenReturn(input);
        return tu;
    }

    /** 跑一轮 acting：next 发 delta + End(state)，返回链上 Flux 输出（budget 触发时含注入消息） */
    private List<Object> runActing(FailureBudgetMiddleware mw, ToolUseBlock tu,
                                   ToolResultState state, String resultText) {
        ActingInput in = new ActingInput(List.of(tu));
        Function<ActingInput, Flux<?>> next = i -> Flux.just(
                new ToolResultTextDeltaEvent("r-1", tu.getId(), tu.getName(), resultText),
                new ToolResultEndEvent("e-1", null, "r-1", tu.getId(), tu.getName(), state));
        return mw.onActing(mock(Agent.class), ctx(), in,
                (Function<ActingInput, Flux<AgentEvent>>) next)
                .collectList().block().stream().map(e -> (Object) e).toList();
    }

    @Test
    void sameSignatureFailuresInjectBudgetMessageOnThreshold() {
        FailureBudgetMiddleware mw = new FailureBudgetMiddleware(props, catalog);
        ToolUseBlock tu = toolUse("tu-1", "finance__query", Map.of("status", "PENDING"));
        runActing(mw, tu, ToolResultState.ERROR, "[ERROR] timeout");       // 第 1 次：不触发
        List<Object> second = runActing(mw,
                toolUse("tu-2", "finance__query", Map.of("status", "PENDING")),
                ToolResultState.ERROR, "[ERROR] timeout");                  // 第 2 次：达阈值
        boolean injected = second.stream().anyMatch(o -> o instanceof ToolResultTextDeltaEvent d
                && Boolean.TRUE.equals(d.getMetadata().get("sunshine.budgetReminder"))
                && d.getDelta().contains("finance__query") && d.getDelta().contains("连续失败 2 次"));
        assertThat(injected).isTrue();
    }

    @Test
    void successResetsBothDimensions() {
        FailureBudgetMiddleware mw = new FailureBudgetMiddleware(props, catalog);
        runActing(mw, toolUse("tu-1", "finance__query", Map.of("a", 1)), ToolResultState.ERROR, "[ERROR] x");
        runActing(mw, toolUse("tu-2", "finance__query", Map.of("a", 1)), ToolResultState.SUCCESS, "ok");
        // 再失败 1 次：sig 重新从 1 起，不达阈值 2 → 不注入
        List<Object> out = runActing(mw,
                toolUse("tu-3", "finance__query", Map.of("a", 1)), ToolResultState.ERROR, "[ERROR] x");
        assertThat(out.stream().noneMatch(o -> o instanceof ToolResultTextDeltaEvent d
                && Boolean.TRUE.equals(d.getMetadata().get("sunshine.budgetReminder")))).isTrue();
    }

    @Test
    void interruptedDoesNotCountIntoErrorBudget() {
        FailureBudgetMiddleware mw = new FailureBudgetMiddleware(props, catalog);
        runActing(mw, toolUse("tu-1", "sandbox__exec", Map.of()), ToolResultState.INTERRUPTED, "");
        List<Object> out = runActing(mw, toolUse("tu-2", "sandbox__exec", Map.of()),
                ToolResultState.INTERRUPTED, "");
        assertThat(out.stream().noneMatch(o -> o instanceof ToolResultTextDeltaEvent d
                && Boolean.TRUE.equals(d.getMetadata().get("sunshine.budgetReminder")))).isTrue();
    }

    @Test
    void budgetTriggersOnlyOncePerRun() {
        FailureBudgetMiddleware mw = new FailureBudgetMiddleware(props, catalog);
        for (int i = 1; i <= 3; i++) {
            List<Object> out = runActing(mw,
                    toolUse("tu-" + i, "finance__query", Map.of("status", "PENDING")),
                    ToolResultState.ERROR, "[ERROR] t");
            boolean injected = out.stream().anyMatch(o -> o instanceof ToolResultTextDeltaEvent d
                    && Boolean.TRUE.equals(d.getMetadata().get("sunshine.budgetReminder")));
            assertThat(injected).isEqualTo(i == 2); // 仅达阈值那次触发
        }
    }

    @Test
    void todoWriteAndSpawnSubagentExcluded() {
        FailureBudgetMiddleware mw = new FailureBudgetMiddleware(props, catalog);
        for (int i = 1; i <= 3; i++) {
            runActing(mw, toolUse("tu-t" + i, "todo_write", Map.of()), ToolResultState.ERROR, "[ERROR]");
            runActing(mw, toolUse("tu-s" + i, "spawn_subagent", Map.of()), ToolResultState.ERROR, "[ERROR]");
        }
        AgentRunState s = StepEventBridge.runState(BRIDGE);
        assertThat(s.failureCount("tool:todo_write")).isZero();
        assertThat(s.failureCount("tool:spawn_subagent")).isZero();
    }

    @Test
    void disabledSkipsEverything() {
        react.getToolFailureBudget().setEnabled(false);
        FailureBudgetMiddleware mw = new FailureBudgetMiddleware(props, catalog);
        List<Object> out = runActing(mw,
                toolUse("tu-1", "finance__query", Map.of()), ToolResultState.ERROR, "[ERROR]");
        assertThat(out).hasSize(2); // 只透传 delta + End
        assertThat(StepEventBridge.runState(BRIDGE).failureCount("tool:finance__query")).isZero();
    }
}
```

同时补一个 `signature` 纯函数用例（放同文件）：

```java
    @Test
    void signatureNormalizesKeyOrder() {
        String a = FailureBudgetMiddleware.signature("t", Map.of("x", 1, "y", 2));
        String b = FailureBudgetMiddleware.signature("t", Map.of("y", 2, "x", 1));
        assertThat(a).isEqualTo(b).startsWith("t:");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl orchestrator -Dtest=FailureBudgetMiddlewareTest -q`
Expected: 编译错误（`FailureBudgetMiddleware` 不存在）

- [ ] **Step 3: 实现 FailureBudgetMiddleware**

```java
package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.taskboard.TodoTasksBridge;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * 4.7.7 L3 工具失败预算：onActing 统计连续失败（同参数指纹 / 同工具两维度），
 * 达阈值向链上注入瞬态 budget 强提示（下一轮 reasoning 拼进 messages）。
 * 软提示非硬拒：不拦截工具调用；INTERRUPTED（用户取消）不计入；todo_write / spawn_subagent 排除。
 */
@Slf4j
@RequiredArgsConstructor
public class FailureBudgetMiddleware implements MiddlewareBase {

    /** 注入 delta 的 metadata 标记：ProcessingStepMiddleware 据此前缀把 tool 步 after 换文案 */
    public static final String META_BUDGET_REMINDER = "sunshine.budgetReminder";
    /** 注入 delta 的 toolCallId 前缀 */
    public static final String BUDGET_ID_PREFIX = "budget-reminder-";

    private final AgentExecutionProperties executionProperties;
    private final PromptCatalogHolder catalogHolder;

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        AgentExecutionProperties.React.ToolFailureBudget cfg = config();
        String bridgeId = ctx != null ? ctx.get(ProcessingStepMiddleware.CTX_BRIDGE_ID) : null;
        AgentRunState runState = StepEventBridge.runState(bridgeId);
        if (cfg == null || !cfg.isEnabled() || runState == null) {
            return next.apply(input);
        }
        // 入口登记 toolUseId → toolName（End 事件回查参数指纹用）
        for (ToolUseBlock tu : input.toolCalls()) {
            runState.bindToolName(tu.getId(), tu.getName());
        }
        List<AgentEvent> pending = new ArrayList<>();
        return next.apply(input)
                .doOnNext(ev -> {
                    if (ev instanceof ToolResultTextDeltaEvent d) {
                        // 失败时累积错误摘要（占位符 {lastError}）；简单起见直接以本 delta 覆盖
                        String toolName = runState.toolNameOf(d.getToolCallId());
                        if (toolName != null && d.getDelta() != null && d.getDelta().startsWith("[ERROR]")) {
                            runState.recordErrorExcerpt(toolName, firstLine(d.getDelta()));
                        }
                    } else if (ev instanceof ToolResultEndEvent end) {
                        onToolEnd(runState, cfg, end, input, pending);
                    }
                })
                .concatWith(Flux.defer(() -> Flux.fromIterable(pending)))
                .doFinally(sig -> {
                    for (ToolUseBlock tu : input.toolCalls()) {
                        runState.unbindToolName(tu.getId());
                    }
                });
    }

    private void onToolEnd(
            AgentRunState runState, AgentExecutionProperties.React.ToolFailureBudget cfg,
            ToolResultEndEvent end, ActingInput input, List<AgentEvent> pending) {
        String toolName = end.getToolCallName();
        if (toolName == null || TodoTasksBridge.isTodoWrite(toolName) || SpawnSubagentTool.NAME.equals(toolName)) {
            return;
        }
        if (end.getState() == ToolResultState.INTERRUPTED) {
            return; // 用户取消走 4.5.7 同族预算，不混入失败预算
        }
        ToolUseBlock toolUse = findToolUse(input, end.getToolCallId());
        String sigKey = "sig:" + signature(toolName, toolUse != null ? toolUse.getInput() : Map.of());
        String toolKey = "tool:" + toolName;
        if (end.getState() == ToolResultState.ERROR) {
            int sigCount = runState.recordFailure(sigKey);
            int toolCount = runState.recordFailure(toolKey);
            String hitKey = sigCount >= cfg.getSameSignatureMax() ? sigKey
                    : toolCount >= cfg.getPerToolMax() ? toolKey : null;
            int failCount = sigCount >= cfg.getSameSignatureMax() ? sigCount : toolCount;
            if (hitKey != null && runState.markBudgetTriggered(hitKey)) {
                log.info("[FailureBudget] 触发 tool={} key={} failCount={}", toolName, hitKey, failCount);
                pending.add(buildReminder(end.getToolCallId(), toolName, failCount,
                        runState.lastErrorExcerpt(toolName)));
            }
        } else if (end.getState() == ToolResultState.SUCCESS) {
            runState.resetFailure(toolName);
        }
    }

    /** 瞬态强提示：借 ToolResultTextDeltaEvent 携带，runtime drain 时拼成 USER 消息（metadata 标记，不落 AgentState） */
    private AgentEvent buildReminder(String toolUseId, String toolName, int failCount, String lastError) {
        String template = catalogHolder.requireText("react.tool-failure-budget");
        String text = template
                .replace("{toolName}", toolName)
                .replace("{failCount}", String.valueOf(failCount))
                .replace("{lastError}", lastError != null ? lastError : "");
        ToolResultTextDeltaEvent ev = new ToolResultTextDeltaEvent(
                "budget-r", BUDGET_ID_PREFIX + toolUseId, toolName, text);
        ev.getMetadata().put(META_BUDGET_REMINDER, Boolean.TRUE);
        return ev;
    }

    /** 参数指纹：key 排序后 JSON 的 sha1（不做 value 语义归一，避免误合并） */
    public static String signature(String toolName, Map<String, Object> input) {
        String canonical = new TreeMap<>(input != null ? input : Map.of()).toString();
        return toolName + ":" + sha1Hex(canonical);
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolUseBlock findToolUse(ActingInput input, String toolUseId) {
        if (toolUseId == null) {
            return null;
        }
        return input.toolCalls().stream()
                .filter(tu -> toolUseId.equals(tu.getId()))
                .findFirst().orElse(null);
    }

    private static String firstLine(String text) {
        int idx = text.indexOf('\n');
        return idx > 0 ? text.substring(0, idx) : text;
    }

    private AgentExecutionProperties.React.ToolFailureBudget config() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null ? react.getToolFailureBudget() : null;
    }
}
```

注意：`ToolResultTextDeltaEvent` 若无可变 metadata，改用其实际可用的 metadata 入口（构造后 `getMetadata()` 为可变 Map 则直接 put；否则包一层自定义 `AgentEvent` 子类携带标记——实现时以 agentscope-core 源码为准，二选一，禁止第三方案）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl orchestrator -Dtest=FailureBudgetMiddlewareTest -q`
Expected: PASS（7 个用例）

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/FailureBudgetMiddleware.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/agent/FailureBudgetMiddlewareTest.java
git commit -m "feat(orch): FailureBudgetMiddleware same-signature/per-tool budget with transient reminder (4.7.7b)"
```

---

### Task 3: GoalAlignmentMiddleware（L2 目标对齐）

**Files:**
- Create: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/GoalAlignmentMiddleware.java`
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/GoalAlignmentMiddlewareTest.java`

**Interfaces:**
- Consumes: Task 1 的 `AgentRunState`（`nextReasoningIteration` / `noteToolCompleted` / `toolCompletedCount`）、`StepEventBridge.runState` / `StepEventBridge.userQuery`、配置 `GoalCheck`；`PromptCatalogHolder.requireText`；`RuntimeContext.resolveAgentState(ctx, agent)`（agentscope-core）→ `AgentState.getTasksContext().getTasks()`（`io.agentscope.core.state.Task`，`getState()` / `getSubject()`）
- Produces: `GoalAlignmentMiddleware implements MiddlewareBase`，构造签名 `GoalAlignmentMiddleware(AgentExecutionProperties, PromptCatalogHolder)`；任务进度渲染 `static String renderProgress(List<Task> tasks)`（输出 `2/5 已完成 · 进行中：xxx`，供测试断言）

- [ ] **Step 1: 写失败测试**

`Agent`/`AgentState`/`Task` 用 Mockito mock（`RuntimeContext.resolveAgentState` 有 fallback 到 `agent.getAgentState()`，mock Agent 即可控制）：

```java
class GoalAlignmentMiddlewareTest {

    private static final String BRIDGE = "test-bridge";
    private final AgentExecutionProperties props = mock(AgentExecutionProperties.class);
    private final PromptCatalogHolder catalog = mock(PromptCatalogHolder.class);
    private final Agent agent = mock(Agent.class);
    private final AgentState agentState = mock(AgentState.class);
    private AgentExecutionProperties.React react;

    @BeforeEach
    void setUp() {
        react = new AgentExecutionProperties.React();
        react.getGoalCheck().setEnabled(true);
        react.getGoalCheck().setEveryNThink(3);
        when(props.getReact()).thenReturn(react);
        when(catalog.requireText("react.goal-check")).thenReturn(
                "<system-reminder>\n【目标对齐检查】原始任务：{userQuery}\n当前进度：{taskProgress}\n</system-reminder>");
        when(agent.getAgentState()).thenReturn(agentState);
        StepEventBridge.bind(BRIDGE, mock(ProcessingTimelineSession.class), new ConcurrentLinkedQueue<>());
        StepEventBridge.setUserQuery(BRIDGE, "调研三家竞品的定价方案并写报告");
    }

    @AfterEach
    void tearDown() {
        StepEventBridge.resetRegistry();
    }

    private RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u1").sessionId("m1")
                .put(ProcessingStepMiddleware.CTX_BRIDGE_ID, BRIDGE).build();
    }

    private void tasks(Task... ts) {
        TasksContext tc = mock(TasksContext.class);
        when(tc.getTasks()).thenReturn(List.of(ts));
        when(agentState.getTasksContext()).thenReturn(tc);
    }

    private Task task(String subject, Task.State state) {
        Task t = mock(Task.class);
        when(t.getSubject()).thenReturn(subject);
        when(t.getState()).thenReturn(state);
        return t;
    }

    /** 模拟一轮业务 tool 完成（onActing 出口计数） */
    private void completeOneTool(GoalAlignmentMiddleware mw) {
        ToolUseBlock tu = mock(ToolUseBlock.class);
        when(tu.getName()).thenReturn("finance__query");
        when(tu.getId()).thenReturn("tu-x");
        ActingInput in = new ActingInput(List.of(tu));
        Function<ActingInput, Flux<AgentEvent>> next = i -> Flux.just(
                new ToolResultEndEvent("e-1", null, "r-1", "tu-x", "finance__query", ToolResultState.SUCCESS));
        mw.onActing(agent, ctx(), in, next).collectList().block();
    }

    private ReasoningInput runReasoning(GoalAlignmentMiddleware mw, List<Msg> base) {
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        Function<ReasoningInput, Flux<AgentEvent>> next = in -> {
            seen.set(in);
            return Flux.empty();
        };
        mw.onReasoning(agent, ctx(), new ReasoningInput(base, List.of(), null), next)
                .collectList().block();
        return seen.get();
    }

    @Test
    void injectsAtThirdReasoningAfterToolCompleted() {
        tasks(task("整理竞品清单", Task.State.COMPLETED),
              task("收集定价方案", Task.State.IN_PROGRESS),
              task("撰写报告", Task.State.PENDING));
        GoalAlignmentMiddleware mw = new GoalAlignmentMiddleware(props, catalog);
        completeOneTool(mw);
        ReasoningInput r1 = runReasoning(mw, List.of());
        ReasoningInput r2 = runReasoning(mw, List.of());
        ReasoningInput r3 = runReasoning(mw, List.of());
        assertThat(r1.messages()).isEmpty();
        assertThat(r2.messages()).isEmpty();
        assertThat(r3.messages()).hasSize(1);
        Msg reminder = r3.messages().get(0);
        assertThat(reminder.getRole()).isEqualTo(MsgRole.USER);
        assertThat(reminder.getMetadata().get(Msg.METADATA_SYNTHETIC)).isEqualTo(true);
        assertThat(reminder.getMetadata().get(Msg.METADATA_REMINDER_KIND)).isEqualTo("goal_check");
        assertThat(reminder.getTextContent()).contains("调研三家竞品的定价方案并写报告")
                .contains("1/3 已完成").contains("收集定价方案");
    }

    @Test
    void noInjectionWithoutTasksContext() {
        TasksContext tc = mock(TasksContext.class);
        when(tc.getTasks()).thenReturn(List.of());
        when(agentState.getTasksContext()).thenReturn(tc);
        GoalAlignmentMiddleware mw = new GoalAlignmentMiddleware(props, catalog);
        completeOneTool(mw);
        for (int i = 0; i < 4; i++) {
            assertThat(runReasoning(mw, List.of()).messages()).isEmpty();
        }
    }

    @Test
    void noInjectionWithoutToolCompletedSinceLastInject() {
        tasks(task("收集定价", Task.State.IN_PROGRESS));
        GoalAlignmentMiddleware mw = new GoalAlignmentMiddleware(props, catalog);
        completeOneTool(mw);
        runReasoning(mw, List.of()); // 1
        runReasoning(mw, List.of()); // 2
        runReasoning(mw, List.of()); // 3 → 注入
        // 之后再无 tool 完成：iter 6 不注入
        runReasoning(mw, List.of()); // 4
        runReasoning(mw, List.of()); // 5
        assertThat(runReasoning(mw, List.of()).messages()).isEmpty(); // 6
    }

    @Test
    void disabledSkipsInjection() {
        react.getGoalCheck().setEnabled(false);
        tasks(task("收集定价", Task.State.IN_PROGRESS));
        GoalAlignmentMiddleware mw = new GoalAlignmentMiddleware(props, catalog);
        completeOneTool(mw);
        for (int i = 0; i < 4; i++) {
            assertThat(runReasoning(mw, List.of()).messages()).isEmpty();
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl orchestrator -Dtest=GoalAlignmentMiddlewareTest -q`
Expected: 编译错误（`GoalAlignmentMiddleware` 不存在；`TasksContext` 全限定名 `io.agentscope.core.state.TasksContext` 以源码为准）

- [ ] **Step 3: 实现 GoalAlignmentMiddleware**

```java
package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.taskboard.TodoTasksBridge;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 4.7.7 L2 目标对齐：每 N 轮 reasoning（且期间有业务 tool 完成）向 ReasoningInput
 * 瞬态追加 goal-check 提醒（原始问题 + 任务清单进度），对齐原生 TaskReminderMiddleware
 * 注入模式：不落 AgentState、不参与 compaction、METADATA_SYNTHETIC 标记。MAIN 语义由
 * 上游保证（SUB 无任务板、不注入）；goal-check 不上 Timeline。
 */
@Slf4j
@RequiredArgsConstructor
public class GoalAlignmentMiddleware implements MiddlewareBase {

    private final AgentExecutionProperties executionProperties;
    private final PromptCatalogHolder catalogHolder;

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        AgentRunState runState = runStateOf(ctx);
        if (runState == null) {
            return next.apply(input);
        }
        return next.apply(input)
                .doOnNext(ev -> {
                    // 业务 tool 完成闸门：goal-check 两次注入之间至少要有新进展
                    if (ev instanceof ToolResultEndEvent end && end.getToolCallName() != null
                            && !TodoTasksBridge.isTodoWrite(end.getToolCallName())
                            && !SpawnSubagentTool.NAME.equals(end.getToolCallName())) {
                        runState.noteToolCompleted();
                    }
                });
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        AgentExecutionProperties.React.GoalCheck cfg = config();
        AgentRunState runState = runStateOf(ctx);
        if (cfg == null || !cfg.isEnabled() || runState == null) {
            return next.apply(input);
        }
        int iteration = runState.nextReasoningIteration();
        int everyN = Math.max(1, cfg.getEveryNThink());
        if (iteration % everyN != 0) {
            return next.apply(input);
        }
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        List<Task> tasks = state != null && state.getTasksContext() != null
                ? state.getTasksContext().getTasks() : List.of();
        if (tasks.isEmpty()) {
            return next.apply(input);
        }
        // 工具闸门：本轮注入要求「上次注入后」有新 tool 完成（借 noteToolCompleted 快照比对）
        int completedSnapshot = runState.toolCompletedCount();
        if (iteration > everyN && completedSnapshot == lastInjectedToolCount(runState, completedSnapshot, false)) {
            return next.apply(input);
        }
        markInjectedToolCount(runState, completedSnapshot);
        String userQuery = StepEventBridge.userQuery(bridgeIdOf(ctx));
        String template = catalogHolder.requireText("react.goal-check");
        String text = template
                .replace("{userQuery}", userQuery != null ? userQuery : "")
                .replace("{taskProgress}", renderProgress(tasks));
        Msg reminder = Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .content(TextBlock.builder().text(text).build())
                .metadata(Map.of(
                        Msg.METADATA_SYNTHETIC, true,
                        Msg.METADATA_REMINDER_KIND, "goal_check"))
                .build();
        log.info("[GoalCheck] 注入目标对齐提醒 bridge={} iter={}", bridgeIdOf(ctx), iteration);
        List<Msg> messages = input.messages() != null ? new ArrayList<>(input.messages()) : new ArrayList<>();
        messages.add(reminder);
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    /** 进度渲染：2/5 已完成 · 进行中：xxx（无进行中则仅进度） */
    public static String renderProgress(List<Task> tasks) {
        long completed = tasks.stream().filter(t -> t.getState() == Task.State.COMPLETED).count();
        StringBuilder sb = new StringBuilder()
                .append(completed).append('/').append(tasks.size()).append(" 已完成");
        tasks.stream()
                .filter(t -> t.getState() == Task.State.IN_PROGRESS)
                .findFirst()
                .ifPresent(t -> sb.append(" · 进行中：").append(t.getSubject()));
        return sb.toString();
    }

    // 「上次注入时 tool 完成数」快照存 AgentRunState 扩展字段（实现时加 lastGoalCheckToolCount 的
    // get/set 到 AgentRunState，禁止放 Middleware 实例字段——无状态铁律）
    private int lastInjectedToolCount(AgentRunState s, int current, boolean unused) {
        return s.lastGoalCheckToolCount();
    }

    private void markInjectedToolCount(AgentRunState s, int count) {
        s.setLastGoalCheckToolCount(count);
    }

    private AgentRunState runStateOf(RuntimeContext ctx) {
        return StepEventBridge.runState(bridgeIdOf(ctx));
    }

    private static String bridgeIdOf(RuntimeContext ctx) {
        return ctx != null ? ctx.get(ProcessingStepMiddleware.CTX_BRIDGE_ID) : null;
    }

    private AgentExecutionProperties.React.GoalCheck config() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null ? react.getGoalCheck() : null;
    }
}
```

配套：`AgentRunState` 增补 `private volatile int lastGoalCheckToolCount = -1;` + getter/setter（-1 哨兵表示从未注入；`iteration > everyN` 时才比对，首轮 everyN 注入不受闸门限制）。注意 `noInjectionWithoutToolCompletedSinceLastInject` 用例依赖此语义：首轮注入后快照=N，iter 6 时 completedSnapshot 仍=N → 跳过。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl orchestrator -Dtest=GoalAlignmentMiddlewareTest,AgentRunStateTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/GoalAlignmentMiddleware.java \
        orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentRunState.java \
        orchestrator/src/test/java/com/sunshine/orchestrator/agent/GoalAlignmentMiddlewareTest.java
git commit -m "feat(orch): GoalAlignmentMiddleware periodic goal-check reminder (4.7.7c)"
```

---

### Task 4: 接线 — Middleware 链 + budget 瞬态消息拼装 + tool 步换文案

**Files:**
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddlewareFactory.java`
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java:53-61`（builder middleware 处）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java`（`onActing` doOnNext 与 `completeToolStep`）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java`（`routeDeltaToBridge` 附近）
- Modify: `orchestrator/src/main/java/com/sunshine/orchestrator/processing/TimelineStepLabelService.java`（或对应 Nacos 文案服务，按现有 `timeline.steps` 读取模式）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/ProcessingStepMiddlewareTest.java`（追加用例）
- Test: `orchestrator/src/test/java/com/sunshine/orchestrator/agent/MiddlewareChainWiringTest.java`（新建）

**Interfaces:**
- Consumes: Task 2 的 `FailureBudgetMiddleware.META_BUDGET_REMINDER` / `BUDGET_ID_PREFIX`；Task 3 的 `GoalAlignmentMiddleware`
- Produces: `ProcessingStepMiddlewareFactory.sharedChain()` → `List<MiddlewareBase>`（顺序固定：ProcessingStep → FailureBudget → GoalAlignment）；`ProcessingStepMiddleware` 新增构造参数 `TimelineStepLabelService`（或对应接口，按现有注入模式）；budget 瞬态消息经 `StepEventBridge.emitSyntheticMessage(String bridgeId, String text)`（新门面方法）进入下一轮 reasoning messages

- [ ] **Step 1: budget 瞬态消息通道（写失败测试）**

`ReActAgentRuntimeTest` 追加：drain 到带 `META_BUDGET_REMINDER` 标记的 token 时，将其文本以 `MsgRole.USER` + `METADATA_SYNTHETIC` 消息追加进**下一次** `streamEvents` 调用的 inputs（实现机制：runtime 持有 `pendingSyntheticMsgs` 队列，budget delta → 转 Msg 入队；下一轮 reasoning 前 flush 进 input messages）。

简化实现（避免改 ReActAgent 内部）：`StepEventBridge` 新增：

```java
/** 瞬态提醒消息（budget 强提示）：GoalAlignmentMiddleware 下一轮 onReasoning 出口统一 flush */
public static void emitSyntheticMessage(String bridgeId, String text) {
    registry.appendSyntheticMessage(bridgeId, text);
}

/** GoalAlignmentMiddleware.onReasoning 末尾调用：取出并清空本 run 待注入的瞬态消息 */
public static List<String> drainSyntheticMessages(String bridgeId) {
    return registry.drainSyntheticMessages(bridgeId);
}
```

落地：`GoalAlignmentMiddleware.onReasoning` 在自身 goal-check 判断之后，`next.apply` 之前统一 `drainSyntheticMessages` 逐条转 reminder Msg 追加（这样 budget 提示天然排在 goal-check 之后，对齐 spec §3 顺序约定）。**注意**：goal-check 未触发/未启用时也必须 drain（budget 独立生效），把 drain 逻辑放在 onReasoning 最前、enabled 判断之前。

`MiddlewareChainWiringTest`：

```java
@Test
void budgetReminderFlowsIntoNextReasoningAsSyntheticUserMessage() {
    // 装配：ProcessingStep → FailureBudget → GoalAlignment（同工厂顺序）
    // 第 1 轮 acting 两次同参 ERROR → budget delta 经 StepEventBridge.emitSyntheticMessage 入队
    // 第 2 轮 reasoning：GoalAlignmentMiddleware drain → messages 尾部含 budget 文本，
    //   metadata SYNTHETIC + REMINDER_KIND="tool_failure_budget"，且在 goal_check 消息之后
}

@Test
void drainHappensEvenWhenGoalCheckDisabled() {
    react.getGoalCheck().setEnabled(false);
    // budget 触发后下一轮 reasoning 仍含 budget 消息
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl orchestrator -Dtest=MiddlewareChainWiringTest -q`
Expected: 失败（`emitSyntheticMessage` / `drainSyntheticMessages` 不存在）

- [ ] **Step 3: 实现通道 + 接线**

1. `StepEventBridgeRegistry`：`Map<String, Queue<String>> syntheticMsgs` 随 bridge 创建/回收；`appendSyntheticMessage` / `drainSyntheticMessages`（返回并清空）。
2. `ProcessingStepMiddleware.onActing` 的 `doOnNext` 分支最前增加：

```java
if (ev instanceof ToolResultTextDeltaEvent d
        && Boolean.TRUE.equals(d.getMetadata().get(FailureBudgetMiddleware.META_BUDGET_REMINDER))) {
    StepEventBridge.emitSyntheticMessage(bridgeId, d.getDelta());
    // 不回传：budget delta 不是业务工具结果，禁止进模型 tool result 流
    return; // doOnNext 内用标志位跳过后续处理
}
```

同时在 `resultTextById` 中记录该 toolCallId 前缀为 `budget-reminder-` 的标记，供 `completeToolStep` 判定。

3. `GoalAlignmentMiddleware.onReasoning` 最前（enabled 判断之前）drain 并追加 budget 消息（`METADATA_REMINDER_KIND="tool_failure_budget"`）。
4. `ProcessingStepMiddlewareFactory`：

```java
public List<MiddlewareBase> sharedChain() {
    // 顺序即 spec §3：Processing（timeline）→ FailureBudget（先记录失败/发提醒）→ GoalAlignment（drain+注入）
    return List.of(shared(), failureBudget(), goalAlignment());
}
```

三个实例各自双检锁缓存；`failureBudget` / `goalAlignment` 构造注入 `AgentExecutionProperties` + `PromptCatalogHolder`（工厂新增依赖）。

5. `ReActAgentFactory.create`：`.middleware(middlewareFactory.shared())` → `middlewareFactory.sharedChain().forEach(builder::middleware)`。
6. tool 步换文案：`completeToolStep` 开头判定 `toolUseId` 是否带 budget 标记，命中则 `summaryLine = timelineLabels.text("tool-failure-budget.after")`（TimelineStepLabelService 按现有 `timeline.steps.*` 读取；若该服务只有类型化方法，新增 `toolFailureBudgetAfter()` 方法读 Nacos `agent.timeline.steps.tool-failure-budget.after`，缺省「连续失败，需调整方案」走配置默认值**而非**代码兜底字符串——文案进 Nacos）。

- [ ] **Step 4: ProcessingStepMiddlewareTest 追加 budget 用例**

```java
@Test
void budgetMarkedToolStepCompletesWithBudgetAfterText() {
    // 构造 toolUseId=budget-reminder-tu-1 的 ToolResultEndEvent 路径或
    // 直接在 completeToolStep 前注入 budget 标记 → 断言 session.completeToolStepForToolUse
    // 收到的 summaryLine 为「连续失败，需调整方案」（mock TimelineStepLabelService）
}
```

- [ ] **Step 5: 全量回归**

Run: `mvn test -pl orchestrator -Dtest='ProcessingStepMiddlewareTest,MiddlewareChainWiringTest,ReActAgentRuntimeTest,FailureBudgetMiddlewareTest,GoalAlignmentMiddlewareTest,ReactTaskBoardTest' -q && mvn compile -pl orchestrator -am -q`
Expected: 全部 PASS（重点确认 `ProcessingStepMiddlewareTest` 既有用例不因构造参数新增而破坏——同步更新 `newMiddleware()` 辅助方法的 mock）

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/ \
        orchestrator/src/main/java/com/sunshine/orchestrator/processing/ \
        orchestrator/src/test/java/com/sunshine/orchestrator/agent/
git commit -m "feat(orch): wire goal-check/failure-budget middleware chain + budget step label (4.7.7b/c wiring)"
```

---

### Task 5: Nacos 配置 + Catalog 模板

**Files:**
- Modify: `docs/nacos/sunshine-orchestrator.yaml`
- prompt-manager DB（`/prompts` Admin UI 手工录入，不入库 SQL 种子——对齐「提示词 SSOT = Catalog」运行时维护模式；若项目已有 Catalog 种子 SQL 模式则从其模式）

- [ ] **Step 1: Nacos 配置**

`agent.execution.react` 下追加：

```yaml
agent:
  execution:
    react:
      goal-check:                 # 4.7.7 L2 目标对齐（灰度，先 false）
        enabled: false
        every-n-think: 3
      tool-failure-budget:        # 4.7.7 L3 工具失败预算（灰度，先 false）
        enabled: false
        same-signature-max: 2
        per-tool-max: 3
  timeline:
    steps:
      tool-failure-budget:
        after: 连续失败，需调整方案
```

- [ ] **Step 2: sync + 重启**

```bash
python scripts/sync_nacos.py && python scripts/start.py --only orchestrator
```

（`--only` 参数以 `start.py` 实际支持为准；不支持则全量重启）

- [ ] **Step 3: Catalog 模板（`/prompts` 录入）**

id=`react.goal-check`：

```
<system-reminder>
【目标对齐检查】原始任务：{userQuery}
当前进度：{taskProgress}
在继续行动前请先确认：
1. 已收集的信息是否直接服务于最终输出？剔除与目标无关的探索方向。
2. 当前进行中的子任务完成后，剩余子任务的最短路径是什么？
若发现方向偏离，请用 todo_write 修正任务清单再继续。
</system-reminder>
```

id=`react.tool-failure-budget`：

```
<system-reminder>
【执行受阻】工具 {toolName} 已连续失败 {failCount} 次（最近错误：{lastError}）。
禁止再用相同思路重试。你必须立即三选一：
1. 换用其他工具或备选数据源获取等价信息；
2. 跳过该子任务，先推进不受影响的任务（用 todo_write 调整状态）；
3. 若该数据为关键路径且确实无法获取，向用户如实说明现状与影响，然后基于已有信息收束作答。
</system-reminder>
```

- [ ] **Step 4: 冒烟验证**

开启 `enabled=true` 后发起一句多步调研对话，orchestrator 日志应见 `[GoalCheck] 注入目标对齐提醒`；关闭后无该日志。

- [ ] **Step 5: Commit**

```bash
git add docs/nacos/sunshine-orchestrator.yaml
git commit -m "feat(config): goal-check & tool-failure-budget nacos flags + timeline label (4.7.7d)"
```

---

### Task 6: Live 验收脚本

**Files:**
- Create: `scripts/verify_goal_alignment_live.py`

**Interfaces:**
- Consumes: 现有 live 脚本模式（参考 `scripts/verify_react_taskboard_live.py` 的 SSE 断言与 `sunshine_lib.py` 的会话工具）

- [ ] **Step 1: 写脚本**

断言四组（G1–G4 对齐 spec §9.2）：

| # | 场景 | 断言 |
|---|------|------|
| G1 | taskboard + goal-check 开启，多步调研句 | orchestrator 日志 ≥1 次 `[GoalCheck] 注入`；SSE 含 `phase=tasks`；正文正常完成 |
| G2 | budget 开启 + 构造持续失败（临时把某工具改为不可用或诱导错误参数） | 日志 `[FailureBudget] 触发`；该 tool 步 SSE `summary.after`=「连续失败，需调整方案」；模型后续不再同参数重试（断言同 tool 调用次数 ≤ same-signature-max+1） |
| G3 | 两个开关均 false | 无 `[GoalCheck]` / `[FailureBudget]` 日志；行为与基线一致 |
| G4 | 简单句「你好」（开关开启） | 无注入日志、无 tasks 步 |

- [ ] **Step 2: 跑 Live**

```bash
python scripts/verify_goal_alignment_live.py
```

Expected: 4 组全 PASS（G2 失败场景构造若成本高，允许用单测矩阵替代并在脚本中标注 skip 原因）

- [ ] **Step 3: Commit**

```bash
git add scripts/verify_goal_alignment_live.py
git commit -m "test: live verify goal-check & failure-budget hooks (4.7.7e)"
```

---

## 执行注意

- **先评审 §1.3 功能识别**：本计划改动 orchestrator 时间线/Agent 链路，动手前须按 CLAUDE.md 获确认
- Middleware 顺序即契约：ProcessingStep → FailureBudget → GoalAlignment；改动顺序需重评审
- `AgentRunState.lastGoalCheckToolCount` 哨兵 -1 语义：首轮 everyN 注入不受工具闸门限制
- budget 提醒**不上** Timeline、不进 tool result 流；goal-check 提醒对用户透明
- 开关默认 false：合并后主行为零变化；灰度在 Nacos 逐租户/逐环境开启
- 改 orchestrator 后：编译 → 重启 → 跑 live/e2e 留记录（CLAUDE.md §常用命令）
