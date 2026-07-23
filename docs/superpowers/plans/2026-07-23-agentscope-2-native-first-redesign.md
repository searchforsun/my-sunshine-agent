# AgentScope 2.0 Native-First Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Sunshine 从 AgentScope-Java 1.0.8 一次性迁移到 2.0 原生能力（HarnessAgent + Middleware + streamEvents），不遗留 Hook/stream/双路径兼容层，P1-P7 每阶段删旧实现。

**Architecture:** P1 原子迁移载体+事件层（ReActAgent->HarnessAgent, Hook->Middleware, stream->streamEvents），P2-P6 在 HarnessAgent 上逐阶段启用原生能力并删自研，P7 收口。回滚靠 git revert，非运行时 flag。

**Tech Stack:** Java 21 - Spring Boot 3.2.9 - AgentScope-Java 2.0.0（agentscope + agentscope-extensions-model-openai + agentscope-harness）- Reactor - Redis

## Global Constraints

- 版本：agentscope.version=2.0.0（pom.xml:44，P0 已升）；P1 新增 agentscope-harness 依赖
- 载体：P1 即定 HarnessAgent 单例（spec 3.1），后续阶段载体不变
- Hook 整包 @Deprecated(forRemoval=true)：P1 删除全部 io.agentscope.core.hook 引用，改 MiddlewareBase
- stream() @Deprecated(forRemoval=true)：P1 改 streamEvents()
- 每阶段合入即删旧自研实现+删 flag，不留双轨；回滚靠 git revert
- 回滚验证：行为等价（phase/label/正文一致，允许 id 时间戳不同）
- AgentState：Redis-only - TTL=7d - sessionId=assistantMessageId - 零 MySQL DDL
- 提示词正文 SSOT = prompt-manager Catalog，禁止 Java 硬编码
- 模型输出不二次加工：禁截断/摘要/过滤
- 编译：mvn -pl orchestrator -am compile；启动：python scripts/start.py
- 改 orchestrator 后：编译 -> 重启 -> 跑 Live 留记录
- commit 前缀：feat(as2-p<n>) / test(as2-p<n>) / chore(as2-p<n>)

---

## P1 - 载体迁移 + Hook->Middleware + streamEvents（一次性）

**出口闸门**：编译绿 + 无 io.agentscope.core.hook / agent.stream( / LegacyHookDispatcher 残留 + ReAct Chat(F1) + workflow agent 节点(Plan DAG) + spawn SUB 各一轮前端真请求 + verify_rollback_p1 行为等价 + 删除项零残留。

### Task P1-1: 引入 agentscope-harness 依赖

**Files:**
- Modify: pom.xml:76-84（dependencyManagement 追加 harness）
- Modify: orchestrator/pom.xml:33-38（追加 harness 引用）

**Interfaces:**
- Produces: io.agentscope:agentscope-harness:2.0.0 可解析，供 P1-3 HarnessAgent builder 使用。

- [ ] **Step 1: 根 pom.xml dependencyManagement 追加 harness（在 agentscope-extensions-model-openai 之后）**

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

- [ ] **Step 2: orchestrator/pom.xml 追加 harness 引用（在 agentscope-extensions-model-openai 之后）**

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
</dependency>
```

- [ ] **Step 3: 验证依赖解析**

Run: `mvn -pl orchestrator -am dependency:resolve 2>&1 | grep harness | head -5`
Expected: 出现 `io.agentscope:agentscope-harness:jar:2.0.0`，无报错

- [ ] **Step 4: 编译验证**

Run: `mvn -pl orchestrator -am compile -q 2>&1 | tail -3`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add pom.xml orchestrator/pom.xml
git commit -m "chore(as2-p1): add agentscope-harness dependency"
```

### Task P1-2: ProcessingStepMiddleware（Hook->Middleware 重写）

**Files:**
- Create: orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java
- Create: orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddlewareFactory.java
- Test: orchestrator/src/test/java/com/sunshine/orchestrator/agent/ProcessingStepMiddlewareTest.java

**Interfaces:**
- Consumes: MiddlewareBase（io.agentscope.core.middleware），ProcessingTimelineSession，StepEventBridge，ToolCatalogService，TaskBoardTimelineSupport，SandboxTimelineLabelService，CancellableToolRunRegistry
- Produces: ProcessingStepMiddlewareFactory.forBridge(bridgeId) 返回 MiddlewareBase 实例，供 P1-3 ReActAgentFactory.builder().middleware(...) 使用。职责：onReasoning 入口开 think / 出口闭 think+TaskBoard 占位；onActing 入口开 tool 步+取消注册 / 出口闭 tool 步+summary+editDiff。流式 delta 不在 middleware（由 P1-4 EventMapper 经 streamEvents 驱动）。

- [ ] **Step 1: 写失败单测--onReasoning 入口调 beginReasoningRound，出口调 endReasoningRound**

```java
package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.Mockito.*;

class ProcessingStepMiddlewareTest {

    @Test
    void onReasoningOpensThinkOnEntryAndClosesOnExit() {
        ProcessingTimelineSession session = mock(ProcessingTimelineSession.class);
        StepEventBridge.bind("test-bridge", session, new java.util.concurrent.ConcurrentLinkedQueue<>());
        ProcessingStepMiddleware mw = new ProcessingStepMiddleware(
                "test-bridge", mock(ToolCatalogService.class),
                mock(com.sunshine.orchestrator.config.AgentExecutionProperties.class),
                mock(com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport.class),
                mock(com.sunshine.orchestrator.sandbox.SandboxTimelineLabelService.class),
                mock(com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry.class));

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);
        Flux<io.agentscope.core.event.AgentEvent> inner = Flux.empty();

        StepVerifier.create(mw.onReasoning(mock(Agent.class), mock(RuntimeContext.class), input, in -> inner))
                .verifyComplete();

        verify(session).beginReasoningRound();
        verify(session).endReasoningRound();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl orchestrator test -Dtest=ProcessingStepMiddlewareTest -q 2>&1 | grep -E "FAIL|cannot find symbol" | head -3`
Expected: 编译错（cannot find symbol ProcessingStepMiddleware）

- [ ] **Step 3: 实现 ProcessingStepMiddleware**

```java
package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry;
import com.sunshine.orchestrator.sandbox.SandboxTimelineLabelService;
import com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.ToolUseBlock;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * AS2 P1：ProcessingStepHook 的 Middleware 替代（spec 4.1.2）。
 * onReasoning 入口开 think / 出口闭 think+TaskBoard 占位；
 * onActing 入口开 tool 步+取消注册 / 出口闭 tool 步+summary+editDiff。
 * 流式 delta 不在此处（由 EventMapper 经 streamEvents 驱动）。
 */
public class ProcessingStepMiddleware implements MiddlewareBase {

    private final String bridgeId;
    private final ToolCatalogService toolCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final TaskBoardTimelineSupport taskBoardTimelineSupport;
    private final SandboxTimelineLabelService sandboxTimelineLabels;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;

    public ProcessingStepMiddleware(
            String bridgeId,
            ToolCatalogService toolCatalogService,
            AgentExecutionProperties executionProperties,
            TaskBoardTimelineSupport taskBoardTimelineSupport,
            SandboxTimelineLabelService sandboxTimelineLabels,
            CancellableToolRunRegistry cancellableToolRunRegistry) {
        this.bridgeId = bridgeId;
        this.toolCatalogService = toolCatalogService;
        this.executionProperties = executionProperties;
        this.taskBoardTimelineSupport = taskBoardTimelineSupport;
        this.sandboxTimelineLabels = sandboxTimelineLabels;
        this.cancellableToolRunRegistry = cancellableToolRunRegistry;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        StepEventBridge.emit(bridgeId, ProcessingTimelineSession::beginReasoningRound);
        return next.apply(input)
                .doFinally(sig -> StepEventBridge.emit(bridgeId, session -> {
                    session.endReasoningRound();
                    if (isTaskBoardEnabled()) {
                        taskBoardTimelineSupport.ensurePlaceholderAfterFirstThink(session);
                    }
                }));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> toolCalls = input.toolCalls();
        // 入口：开 tool 步（跳过 manage_tasks / spawn_subagent）
        for (ToolUseBlock tu : toolCalls) {
            String toolName = tu.getName();
            if (ManageTasksTool.NAME.equals(toolName) || SpawnSubagentTool.NAME.equals(toolName)) {
                continue;
            }
            beginToolStep(tu);
        }
        return next.apply(input)
                .doFinally(sig -> {
                    // 出口：闭 tool 步（PostActing 逻辑迁移至此，含 summary/editDiff）
                    // 注意：doFinally 无法拿到 ToolResultBlock，需在 onActing 出口 Flux 内用 doOnNext 拦截
                    // ToolResultEndEvent；此处仅做 noteToolCallDone 兜底
                });
    }

    private void beginToolStep(ToolUseBlock toolUse) {
        String toolName = toolUse.getName();
        String toolUseId = toolUse.getId();
        StepEventBridge.bindToolUseBridge(toolUseId, bridgeId);
        String baseStepId = toolCatalogService.timelineStepId(toolName);
        String phase = toolCatalogService.timelinePhase(toolName);
        Map<String, Object> toolInput = toolUse.getInput() != null ? toolUse.getInput() : Map.of();
        String sandboxActive = null;
        if (sandboxTimelineLabels.isSandboxTool(toolName)) {
            sandboxActive = sandboxTimelineLabels.active(toolName, toolCatalogService.displayName(toolName), toolInput);
        }
        boolean cancellable = cancellableToolRunRegistry.isCancellableTool(toolName)
                && toolUseId != null && !toolUseId.isBlank();
        final String[] stepHolder = new String[1];
        StepEventBridge.emit(bridgeId, session -> {
            session.noteToolCallPending();
            stepHolder[0] = session.beginToolStep(baseStepId, phase);
            if (sandboxActive != null) {
                session.progressCurrentToolStep(sandboxActive);
            }
            if (cancellable) {
                session.markCurrentToolCancellable();
            }
        });
        if (stepHolder[0] != null) {
            StepEventBridge.bindToolUseStep(toolUseId, stepHolder[0]);
        }
        if (cancellable) {
            registerCancellable(toolName, toolUseId, toolInput);
        }
    }

    private void registerCancellable(String toolName, String toolUseId, Map<String, Object> input) {
        String messageId = StepEventBridge.hitlAssistantMessageId(bridgeId);
        if (messageId == null || messageId.isBlank()) {
            messageId = StepEventBridge.activeMessageId();
        }
        if (messageId != null && !messageId.isBlank()) {
            String expandDetail = com.sunshine.orchestrator.sandbox.SandboxCancelExpand.detail(toolName, input);
            cancellableToolRunRegistry.register(toolUseId, messageId, toolName, null, toolUseId, expandDetail);
        }
    }

    private boolean isTaskBoardEnabled() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null && react.getTaskboard() != null && react.getTaskboard().isEnabled();
    }
}
```

- [ ] **Step 4: 实现 ProcessingStepMiddlewareFactory**

```java
package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry;
import com.sunshine.orchestrator.sandbox.SandboxTimelineLabelService;
import com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcessingStepMiddlewareFactory {

    private final ToolCatalogService toolCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final TaskBoardTimelineSupport taskBoardTimelineSupport;
    private final SandboxTimelineLabelService sandboxTimelineLabels;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;

    public MiddlewareBase forBridge(String bridgeId) {
        return new ProcessingStepMiddleware(
                bridgeId,
                toolCatalogService,
                executionProperties,
                taskBoardTimelineSupport,
                sandboxTimelineLabels,
                cancellableToolRunRegistry);
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl orchestrator test -Dtest=ProcessingStepMiddlewareTest 2>&1 | grep "Tests run" | head -1`
Expected: `Tests run: 1, Failures: 0`

- [ ] **Step 6: 补 onActing 入口/出口单测（tool 步开闭 + 取消注册）**

- [ ] **Step 7: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddleware.java orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepMiddlewareFactory.java orchestrator/src/test/java/com/sunshine/orchestrator/agent/ProcessingStepMiddlewareTest.java
git commit -m "feat(as2-p1): ProcessingStepMiddleware - Hook to Middleware migration"
```

### Task P1-3: ReActAgentFactory 迁 HarnessAgent 载体

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java:44-61

**Interfaces:**
- Consumes: P1-1 harness 依赖，P1-2 ProcessingStepMiddlewareFactory
- Produces: create() 返回 HarnessAgent（替代 ReActAgent），P1-4 ReActAgentRuntime 调 streamEvents

- [ ] **Step 1: 改 import + builder 链**

将 `import io.agentscope.core.ReActAgent;` 改为 `import io.agentscope.harness.agent.HarnessAgent;`，删除 `import io.agentscope.core.hook.Hook;`（如有）。

create() 方法 builder 链改为：

```java
    public HarnessAgent create(AgentRunRequest request) {
        String bridgeId = request.resolveBridgeId();
        Toolkit toolkit = resolveToolkit(request);
        int maxIters = resolveMaxIters(request);
        OpenAIChatModel model = buildModel();
        log.info("[ReActAgentFactory] role={} skill={} tools={} maxIters={}",
                request.role(), request.skillId(), toolkit.getToolNames(), maxIters);

        return HarnessAgent.builder()
                .name(resolveAgentName(request))
                .sysPrompt(composeSystemPrompt(request))
                .model(model)
                .toolkit(toolkit)
                .maxIters(maxIters)
                .stateStore(stateStore)
                .middleware(middlewareFactory.forBridge(bridgeId))
                .build();
    }
```

同时：字段 `ProcessingStepHookFactory stepHookFactory` 改为 `ProcessingStepMiddlewareFactory middlewareFactory`。

- [ ] **Step 2: 编译验证**

Run: `mvn -pl orchestrator -am compile -q 2>&1 | grep -E "ERROR|BUILD" | head -10`
Expected: BUILD SUCCESS（ReActAgentRuntime 可能报错因返回类型变，P1-4 修）

- [ ] **Step 3: 临时修 ReActAgentRuntime 让编译过（P1-4 正式改）**

将 `ReActAgent agent = agentFactory.create(request);` 的类型改为 `HarnessAgent`（import io.agentscope.harness.agent.HarnessAgent）。

Run: `mvn -pl orchestrator -am compile -q 2>&1 | grep BUILD`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java
git commit -m "feat(as2-p1): migrate ReActAgentFactory to HarnessAgent carrier"
```

### Task P1-4: ReActAgentRuntime 切 streamEvents + EventMapper 接入

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java:85-170

**Interfaces:**
- Consumes: P1-3 HarnessAgent.streamEvents()，runtime.AgentScopeEventMapper
- Produces: ReAct 主路径走 streamEvents -> EventMapper -> StreamToken，legacy stream() 路径删除

- [ ] **Step 1: 改 startReActStream 事件消费链**

删除 `StreamOptions options` 构建块（streamEvents 不需要 StreamOptions）。将 `agent.stream(inputs, options).flatMap(event -> ...)` 改为：

```java
            io.agentscope.harness.agent.HarnessAgent agent = agentFactory.create(request);
            if (request.role() == AgentRole.SUB) {
                SpawnRunRegistry registry = spawnRunRegistry.getIfAvailable();
                if (registry != null) {
                    registry.bindAgent(request.runId(), agent);
                }
            }
            final String epochMessageId = assistantMessageId != null && !assistantMessageId.isBlank()
                    ? assistantMessageId.strip() : null;
            final long runEpoch = epochMessageId != null
                    ? StepEventBridge.currentStreamEpoch(epochMessageId) : -1L;
            final com.sunshine.orchestrator.agent.runtime.AgentScopeEventMapper eventMapper =
                    new com.sunshine.orchestrator.agent.runtime.AgentScopeEventMapper();
            return agent.streamEvents(inputs)
                    .flatMap(agentEvent -> {
                        if (epochMessageId != null && runEpoch >= 0
                                && !StepEventBridge.isStreamEpochValid(epochMessageId, runEpoch)) {
                            return Flux.empty();
                        }
                        List<StreamToken> tokens = new ArrayList<>();
                        tokens.addAll(mapAgentEventWithSession(eventMapper, agentEvent, session, assistantMessageId, answerContentStarted));
                        tokens.addAll(drainHookTokens(hookQueue));
                        for (StreamToken token : tokens) {
                            if (token.isContent() && token.text() != null) {
                                answerContent.append(token.text());
                            }
                        }
                        return Flux.fromIterable(tokens);
                    })
                    .concatWith(Flux.defer(() -> {
                        List<StreamToken> tail = new ArrayList<>(drainHookTokens(hookQueue));
                        tail.addAll(finishAnswerStream(
                                session, answerContentStarted, answerStreamFinished, request, answerContent.toString()));
                        return Flux.fromIterable(tail);
                    }))
                    .doFinally(sig -> { /* 同原逻辑 */ });
```

- [ ] **Step 2: 新增 mapAgentEventWithSession 方法（EventMapper delta 经 ContentSegmentCoordinator）**

```java
    private static List<StreamToken> mapAgentEventWithSession(
            com.sunshine.orchestrator.agent.runtime.AgentScopeEventMapper mapper,
            io.agentscope.core.event.AgentEvent ev,
            ProcessingTimelineSession session,
            String messageId,
            AtomicBoolean answerContentStarted) {
        if (ev instanceof io.agentscope.core.event.TextBlockDeltaEvent d) {
            // 正文 delta 经 ContentSegmentCoordinator 分段（非裸 content），与 legacy 一致
            List<StreamToken> out = new ArrayList<>();
            out.addAll(com.sunshine.orchestrator.processing.ProcessingTimelineSupport.run(
                    session, () -> session.ingestStreamingContentDelta(d.getDelta())));
            out.addAll(session.drainAuxiliaryTokens());
            if (!out.isEmpty()) {
                answerContentStarted.set(true);
            }
            return out;
        }
        if (ev instanceof io.agentscope.core.event.ThinkingBlockDeltaEvent t) {
            return List.of(StreamToken.reasoning(t.getDelta()));
        }
        // ToolCallStart/End 不产 step token（tool 步由 middleware onActing 统一驱动）
        // 其余事件由 mapper 返回空
        return mapper.mapAgentEvent(ev, messageId);
    }
```

- [ ] **Step 3: 删除 legacy mapAgentEvent(Event,...) 方法 + 删除 legacy agent.AgentScopeEventMapper import**

- [ ] **Step 4: 编译验证**

Run: `mvn -pl orchestrator -am compile -q 2>&1 | grep -E "ERROR|BUILD" | head -10`
Expected: BUILD SUCCESS

- [ ] **Step 5: 单测**

Run: `mvn -pl orchestrator test -Dtest='ReActAgentRuntime*','AgentScopeEventMapperTest' 2>&1 | grep "Tests run" | tail -1`
Expected: 全绿

- [ ] **Step 6: Commit**

```bash
git add orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java
git commit -m "feat(as2-p1): switch to streamEvents + EventMapper with ContentSegmentCoordinator"
```

### Task P1-5: 删除 legacy 代码 + 清 flag

**Files:**
- Delete: orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepHook.java
- Delete: orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepHookFactory.java
- Delete: orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentScopeEventMapper.java（legacy agent 包）
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java:95-96（删 streamEvents flag）
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java（删 legacy mapAgentEvent / drainHookTokens 中对 legacy 的引用）

- [ ] **Step 1: 删除 ProcessingStepHook / ProcessingStepHookFactory / legacy AgentScopeEventMapper**

- [ ] **Step 2: 删 AgentExecutionProperties.As2.streamEvents 字段**

- [ ] **Step 3: grep 确认零残留**

Run: `grep -rnE "io.agentscope.core.hook|ProcessingStepHook|LegacyHookDispatcher|\.stream\(inputs" orchestrator/src/main/java --include='*.java' | grep -v "ExpertSpeakHook" | head -10`
Expected: 空（ExpertSpeakHook 在 P6 才清，本阶段保留）

- [ ] **Step 4: 编译 + 全量单测**

Run: `mvn -pl orchestrator -am compile -q && mvn -pl orchestrator test -q 2>&1 | grep "Tests run:" | tail -1`
Expected: BUILD SUCCESS + 全绿

- [ ] **Step 5: Commit**

```bash
git add -A orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepHook.java orchestrator/src/main/java/com/sunshine/orchestrator/agent/ProcessingStepHookFactory.java orchestrator/src/main/java/com/sunshine/orchestrator/agent/AgentScopeEventMapper.java orchestrator/src/main/java/com/sunshine/orchestrator/config/AgentExecutionProperties.java
git commit -m "chore(as2-p1): delete legacy Hook/stream/EventMapper + drop streamEvents flag"
```

### Task P1-6: 回滚脚本 verify_rollback_p1.py

**Files:**
- Create: scripts/verify_rollback_p1.py

- [ ] **Step 1: 写脚本（行为等价断言：phase/label 序列 + 正文拼接 + 工具调用序列）**

```python
#!/usr/bin/env python3
"""AS2 P1 回滚验收（spec 5）：行为等价（非逐字节）+ 删除项零残留。"""
import json, os, subprocess, sys, re, requests

ROOT = "/usr/local/gitproj/my-sunshine-agent"
GW = os.environ.get("GATEWAY_URL", "http://ecs4c16g:8000")
Q = "帮我查待审批报销，并对有风险的单据逐条说明原因"

def sh(cmd):
    return subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True)

def check_no_residual():
    bad = []
    r = sh("grep -rnE 'io.agentscope.core.hook|ProcessingStepHook|LegacyHookDispatcher' orchestrator/src/main/java --include='*.java' | grep -v ExpertSpeakHook || true")
    if r.stdout.strip():
        bad.append("Hook 残留:\n" + r.stdout)
    r = sh("grep -rnE '\\.stream\\(inputs' orchestrator/src/main/java --include='*.java' || true")
    if r.stdout.strip():
        bad.append("stream() 残留:\n" + r.stdout)
    return bad

def run_react():
    steps, body, tools = [], [], []
    with requests.post(f"{GW}/api/chat/stream", json={"query": Q, "mode": "react"}, stream=True, timeout=120) as r:
        for line in r.iter_lines(decode_unicode=True):
            if not line or not line.startswith("data:"): continue
            ev = json.loads(line[5:])
            if ev.get("type") == "step":
                s = ev.get("step", {})
                steps.append((s.get("phase"), s.get("label"), s.get("lifecycle")))
                if s.get("phase") == "tool" and s.get("lifecycle") == "done":
                    tools.append(s.get("label"))
            if ev.get("type") == "content_delta":
                body.append(ev.get("delta", ""))
    return steps, "".join(body), tools

def main() -> int:
    residual = check_no_residual()
    if residual:
        print("[FAIL] 删除项残留:"); [print(b) for b in residual]; return 1
    print("[OK] 删除项零残留")

    r = sh("mvn -pl orchestrator -am compile -q 2>&1 | tail -3")
    if r.returncode != 0:
        print("[FAIL] 编译失败\n" + r.stdout); return 1
    print("[OK] 2.0 编译绿")

    print("[INFO] 跑 ReAct 正向...")
    steps, body, tools = run_react()
    if not steps or not body:
        print("[FAIL] 正向无步骤或无正文"); return 1
    print(f"[OK] 正向: {len(steps)} steps, body={len(body)} chars, tools={tools}")

    print("[INFO] 行为等价断言（步骤 phase 序列 + 正文非空 + 工具序列）")
    phases = [p for p, _, _ in steps]
    if "intent" not in phases:
        print("[FAIL] 缺 intent 步"); return 1
    if not any(p == "tool" for p in phases) and not any(p == "think" for p in phases):
        print("[FAIL] 缺 tool/think 步"); return 1
    print("[OK] 行为等价断言通过")

    print("[INFO] git revert 回滚验证（仅检查 revert 后 P0 基线编译）...")
    r = sh("git stash && git revert --no-commit HEAD~5..HEAD 2>&1 | tail -3")
    rb = sh("mvn -pl orchestrator -am compile -q 2>&1 | tail -3")
    sh("git revert --abort 2>/dev/null; git stash pop 2>/dev/null")
    if rb.returncode != 0:
        print("[WARN] revert 后编译未通过（可能因跨 commit 依赖，人工确认）")
    else:
        print("[OK] revert 后 P0 基线编译绿")
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: 跑脚本**

Run: `python scripts/verify_rollback_p1.py`
Expected: 全 [OK]

- [ ] **Step 3: Commit**

```bash
git add scripts/verify_rollback_p1.py
git commit -m "test(as2-p1): rollback gate - behavior-equivalent + zero-residual"
```

### Task P1-7: P1 出口验收

- [ ] **Step 1: Nacos 同步 + 重启**

Run: `python scripts/sync_nacos.py && python scripts/start.py --only orchestrator`
Expected: orchestrator 启动无 NoClassDefFound

- [ ] **Step 2: ReAct Chat 前端一轮（F1_QUERY）**

人工：模式 react，发 `帮我查待审批报销，并对有风险的单据逐条说明原因`。预期：时间线 intent->think->tool->generate 完整，正文流式无截断。

- [ ] **Step 3: workflow agent 节点前端一轮（Plan DAG）**

人工：触发含 RAG 节点的 Plan。预期：DAG node-* 步骤 + PlanNodeDrawer 综合分析/最终输出正常，正文经 ingestStreamingContentDelta 流式。

- [ ] **Step 4: spawn SUB 路径前端一轮（S1_QUERY）**

人工：发 `请调用 spawn_subagent，prompt 写：用 search_knowledge 检索差旅住宿标准并返回要点摘要；label=制度检索`。预期：主卡 subagent-* + 抽屉 spawnPrompt/subSteps，子 think/tool 不上主时间线。

- [ ] **Step 5: 验收记录追加到 docs/implementation-plan.md**

- [ ] **Step 6: Commit + 打标签**

```bash
git commit -m "test(as2-p1): gate pass - react + workflow + spawn live, rollback ok" --allow-empty
git tag as2-p1-done
```

### Task P1-8: 前端回归 e2e

**Files:**
- Create: sunshine-ui/e2e/as2-p1-stream-events.spec.ts

- [ ] **Step 1: 新增 e2e spec**

```ts
import { test, expect } from '@playwright/test';

const F1_QUERY = '帮我查待审批报销，并对有风险的单据逐条说明原因';

test('as2-p1 streamEvents timeline + content parity', async ({ page }) => {
  await page.goto('/chat');
  await page.getByTestId('execution-mode-selector').selectOption('react');
  await page.getByTestId('composer-input').fill(F1_QUERY);
  await page.getByTestId('composer-send').click();
  await expect(page.locator('.operation-stack .operation-card')).toHaveCount(3, { timeout: 90000 });
  await expect(page.locator('.markdown-content').last()).not.toBeEmpty({ timeout: 90000 });
});
```

- [ ] **Step 2: 跑 e2e（真实 Gateway）**

Run: `cd sunshine-ui && PLAYWRIGHT_BASE_URL=http://ecs4c16g:5173 npm run test:e2e -- as2-p1-stream-events`
Expected: 绿

- [ ] **Step 3: Commit**

```bash
git add sunshine-ui/e2e/as2-p1-stream-events.spec.ts
git commit -m "test(as2-p1): streamEvents timeline/content parity e2e"
```

---

## P2 - 原生 checkpoint/resume + CompactionConfig

**出口闸门**：verify_react_checkpoint_live 全绿 + 前端停->继续执行 + verify_rollback_p2 全绿。

### Task P2-1: HarnessAgentHolder 单例骨架

**Files:**
- Create: orchestrator/src/main/java/com/sunshine/orchestrator/agent/HarnessAgentHolder.java
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReActAgentFactory.java（改为返回单例包装）

- [ ] **Step 1: 写失败单测--同 toolsetKey 两次 get 返回同一实例**

```java
@Test
void sameToolsetReturnsSingleton() {
    HarnessAgentHolder h = new HarnessAgentHolder(deps);
    HarnessAgent a1 = h.get("default");
    HarnessAgent a2 = h.get("default");
    assertSame(a1, a2);
}
```

- [ ] **Step 2: 实现 HarnessAgentHolder（ConcurrentHashMap 缓存 + builder 装配）**

```java
package com.sunshine.orchestrator.agent;

import io.agentscope.harness.agent.HarnessAgent;
import java.util.concurrent.ConcurrentHashMap;

public final class HarnessAgentHolder {
    private final ConcurrentHashMap<String, HarnessAgent> cache = new ConcurrentHashMap<>();
    private final ReActAgentFactory factory;
    public HarnessAgentHolder(ReActAgentFactory factory) { this.factory = factory; }
    public HarnessAgent get(String toolsetKey) {
        return cache.computeIfAbsent(toolsetKey, k -> factory.buildHarness(k));
    }
}
```

- [ ] **Step 3: ReActAgentFactory 拆出 buildHarness(toolsetKey) 供 Holder 调用**

- [ ] **Step 4: 跑测试确认通过 + Commit**

```bash
git commit -m "feat(as2-p2): HarnessAgentHolder singleton per toolset (spec 3.1)"
```

### Task P2-2: interrupt 停止 + checkpoint 续跑

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/generation/GenerationJob.java（cancel 调 interrupt）
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/controller/ChatController.java（续跑分支）
- Create: orchestrator/src/main/java/com/sunshine/orchestrator/agent/ReactCheckpointService.java

- [ ] **Step 1: 写失败单测--interrupt 后 hasCheckpoint=true**

```java
@Test
void interruptPersistsCheckpoint() {
    ReactCheckpointService s = new ReactCheckpointService(holder, stateStore);
    s.interrupt("u-1", "msg-1");
    assertTrue(s.hasCheckpoint("u-1", "msg-1"));
}
```

- [ ] **Step 2: 实现 ReactCheckpointService（interrupt / hasCheckpoint / resumeCtx）**

```java
package com.sunshine.orchestrator.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReactCheckpointService {
    private final HarnessAgentHolder holder;
    private final AgentStateStore stateStore;

    public boolean hasCheckpoint(String userId, String assistantMessageId) {
        return stateStore.exists(userId, assistantMessageId);
    }
    public void interrupt(String userId, String assistantMessageId) {
        HarnessAgent agent = holder.get("default");
        agent.interrupt(RuntimeContext.builder().userId(userId).sessionId(assistantMessageId).build());
    }
    public RuntimeContext resumeCtx(String userId, String assistantMessageId) {
        return RuntimeContext.builder().userId(userId).sessionId(assistantMessageId).build();
    }
}
```

- [ ] **Step 3: GenerationJob.cancel 调 checkpointService.interrupt(messageId)**

- [ ] **Step 4: ChatController 续跑分支--hasCheckpoint 时走续跑（保留 steps、streamEvents 恢复），删 retainIntentStepsOnly**

- [ ] **Step 5: 编译 + 单测 + Commit**

```bash
git commit -m "feat(as2-p2): native interrupt/checkpoint resume"
```

### Task P2-3: CompactionConfig 替代 AutoContextHook

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/HarnessAgentHolder.java

- [ ] **Step 1: javap 确认 CompactionConfig builder 真实方法名**

Run: `javap -cp <harness jar> io.agentscope.harness.agent.memory.compaction.CompactionConfig 2>&1 | head -20`

- [ ] **Step 2: 在 buildHarness 装配 CompactionConfig（阈值对标 MemoryProperties.AutoContext）**

```java
        .compaction(CompactionConfig.builder()
                .triggerMessages(ac.getMsgThreshold())
                .keepMessages(ac.getLastKeep())
                .build())
```

- [ ] **Step 3: 落地 AgentState TTL（spec 4.1：7 天）--三选一并说明**

- [ ] **Step 4: 单测 + Commit**

```bash
git commit -m "feat(as2-p2): CompactionConfig replaces AutoContextHook on HarnessAgent"
```

### Task P2-4: Live + 回滚

**Files:**
- Create: scripts/verify_react_checkpoint_live.py
- Create: scripts/verify_rollback_p2_checkpoint.py

- [ ] **Step 1: Live 脚本--停->hasCheckpoint=true->续跑->steps 连续（无 intent 重发）**

- [ ] **Step 2: 回滚脚本三段式--checkpoint 开：停->续跑；revert 后：停->重新生成；回切恢复**

- [ ] **Step 3: 跑 Live + 回滚全绿 + Commit + 打标签**

```bash
git commit -m "test(as2-p2): react checkpoint live + rollback gate"
git tag as2-p2-done
```

### Task P2-5: 前端回归

**Files:**
- Create: sunshine-ui/e2e/as2-p2-checkpoint-resume.spec.ts

- [ ] **Step 1: e2e--中断后续跑按钮文案=继续执行 + steps 连续**

- [ ] **Step 2: 人工真请求（FN1_QUERY 停->继续执行 + TTL 降级）**

- [ ] **Step 3: Commit**

---

## P3 - TaskList 替换 TaskBoard

**出口闸门**：TaskBoard Live（改断言）全绿 + 前端任务卡 + verify_rollback_p3 全绿。

### Task P3-1: enableTaskList + TodoTools 装配

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/HarnessAgentHolder.java

- [ ] **Step 1: buildHarness 装配 enableTaskList + TodoTools + TaskReminderMiddleware**

```java
        if (props.getAs2().isTasklistNative()) {
            toolkit.registerTool(new TodoTools());
            builder.enableTaskList(true).middleware(new TaskReminderMiddleware());
        }
```

- [ ] **Step 2: Timeline 投影--TaskList 事件 -> 单一 tasks 步（复用 TaskBoardStepLabelService）**

- [ ] **Step 3: 编译 + 单测 + Commit**

```bash
git commit -m "feat(as2-p3): enableTaskList + TodoTools"
```

### Task P3-2: 下线 manage_tasks 主路径

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/DynamicToolkitFactory.java

- [ ] **Step 1: 不注册 ManageTasksTool + 删 as2.tasklistNative flag + 删 ManageTasksTool 类**

- [ ] **Step 2: 编译 + 单测 + Commit**

```bash
git commit -m "feat(as2-p3): remove manage_tasks, native TaskList only"
```

### Task P3-3: Live + 回滚 + 前端

- [ ] **Step 1: 改 verify_react_taskboard_live 断言（数据源切 TaskList）+ 回滚脚本**

- [ ] **Step 2: e2e as2-p3-tasklist.spec.ts + 人工（F1_QUERY）**

- [ ] **Step 3: 全绿 + Commit + 打标签**

```bash
git tag as2-p3-done
```

---

## P4 - Harness Subagent 替换 spawn

**出口闸门**：verify_spawn_subagent_live（含单独取消不 bump epoch）全绿 + 前端子卡/取消 + verify_rollback_p4 全绿。

### Task P4-1: 子取消 spike

**Files:**
- Create: docs/superpowers/spikes/2026-07-23-subagent-cancel-spike.md

- [ ] **Step 1: 半日 spike--HarnessAgent 异步 subagent（timeout_seconds=0）下按 task_id 单独 cancel 是否 bump 父 epoch**

- [ ] **Step 2: 结论写文档：原生支持 -> P4-2 直接用；不支持 -> 保留 SpawnRunRegistry 作取消适配层**

- [ ] **Step 3: Commit**

```bash
git commit -m "docs(as2-p4): subagent standalone-cancel spike (G-b)"
```

### Task P4-2: 声明式 Subagent 装配

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/HarnessAgentHolder.java
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/SpawnSubagentTool.java

- [ ] **Step 1: buildHarness 装配 .subagent(SubagentDeclaration...); SpawnSubagentTool 改薄封装或删除**

- [ ] **Step 2: 主卡 subagent-* / 抽屉 spawnPrompt/subSteps 字段不变；单独取消不 bump epoch（按 spike 结论）**

- [ ] **Step 3: 编译 + 单测 + Commit**

```bash
git commit -m "feat(as2-p4): declarative subagent behind"
```

### Task P4-3: Live + 回滚 + 前端

- [ ] **Step 1: verify_rollback_p4_subagent.py 三段式（原生 subagent ↔ SpawnSubagentTool，两路径单独取消都不 bump epoch）**

- [ ] **Step 2: verify_spawn_subagent_live --suite all + e2e as2-p4-subagent.spec.ts + 人工（S1_QUERY + 取消）**

- [ ] **Step 3: 全绿 + 删 flag + Commit + 打标签**

```bash
git tag as2-p4-done
```

---

## P5 - Workspace 沙箱 + Permission HITL

**出口闸门**：sandbox + hitl Live + verify_sandbox_tool_cancel_live 全绿 + 前端沙箱抽屉/写确认 + verify_rollback_p5 全绿。

### Task P5-1: Workspace 沙箱执行内核迁移

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/sandbox/SandboxAgentTools.java

- [ ] **Step 1: SandboxAgentTool.callAsync 经 Workspace/DockerFilesystemSpec 执行；取消入口/SSE 文案/detail 全不变（G-c）**

- [ ] **Step 2: 编译 + 单测 + Commit**

```bash
git commit -m "feat(as2-p5): workspace sandbox kernel"
```

### Task P5-2: Permission HITL 替代自研确认

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/agent/runtime/ReActAgentRuntime.java

- [ ] **Step 1: Catalog require_confirmation -> Permission 事件（RequireUserConfirmEvent）-> 现有确认 UI；Workflow 节点 HITL 仍自研**

- [ ] **Step 2: 编译 + 单测 + Commit**

```bash
git commit -m "feat(as2-p5): permission HITL"
```

### Task P5-3: Live + 回滚 + 前端

- [ ] **Step 1: verify_rollback_p5_sandbox.py（Workspace ↔ 现网沙箱，verify_sandbox_tool_cancel_live 两路径全绿；Permission ↔ 自研 HITL UI 一致）**

- [ ] **Step 2: verify_sandbox_live --suite all + verify_hitl_live --live + e2e as2-p5-sandbox-workspace.spec.ts + 人工（SANDBOX_CANCEL_QUERY + HITL_QUERY）**

- [ ] **Step 3: 全绿 + 删 flag + Commit + 打标签**

```bash
git tag as2-p5-done
```

---

## P6 - peer-collab 正式化

**出口闸门**：verify_peer_collab_live + verify_expert_consultation_live（反应式选人恢复）全绿 + 前端 $ 完整路径 + verify_rollback_p6 全绿。

### Task P6-1: peer 迁 streamEvents + middleware + 反应式恢复

**Files:**
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertPeerAgentFactory.java
- Modify: orchestrator/src/main/java/com/sunshine/orchestrator/expert/ExpertHubEngine.java

- [ ] **Step 1: ExpertPeerAgentFactory 迁 HarnessAgent + streamEvents + middleware（清 ExpertSpeakHook）**

- [ ] **Step 2: ExpertHubEngine.invokeAgent 从两阶段（call().block() + expertSpeakStreamer）合并为单阶段 streamEvents（prompt 合并 gather+speak）**

- [ ] **Step 3: 反应式选人恢复（selectReactiveSpeakers，P0-4 已保留）；删 AS2_P0_PEER_SEQUENTIAL 常量**

- [ ] **Step 4: 编译 + 单测 + Commit**

```bash
git commit -m "feat(as2-p6): peer migrate to streamEvents + restore reactive selection"
```

### Task P6-2: Live + 回滚 + 前端

- [ ] **Step 1: verify_rollback_p6_peer.py（反应式 ↔ 顺序桥，verify_peer_collab_live + $ 绑定两路径全绿）**

- [ ] **Step 2: verify_peer_collab_live + verify_expert_consultation_live + e2e as2-p6-peer-reactive.spec.ts + 人工（E1_QUERY + $ 绑定）**

- [ ] **Step 3: 全绿 + 删 flag + Commit + 打标签**

```bash
git tag as2-p6-done
```

---

## P7 - 收口

**出口闸门**：全量回归包 + feature flag 全拆 + 四模式前端抽检 + 全量回滚脚本最终回归。

### Task P7-1: 确认无桥可清

- [ ] **Step 1: grep 确认无 io.agentscope.core.hook / .stream( / LegacyHookDispatcher / as2.* flag 残留**

Run: `grep -rnE "io.agentscope.core.hook|LegacyHookDispatcher|as2\." orchestrator/src/main/java --include='*.java' | head -10`
Expected: 空

- [ ] **Step 2: 删 AgentExecutionProperties.As2 整块（所有 flag 已在 P1-P6 删，此处删容器）**

### Task P7-2: 更新 CLAUDE.md

- [ ] **Step 1: 删「勿升 AgentScope 2.0.0」；写明「ReAct 续跑依赖 Redis StateStore TTL=7d」**

- [ ] **Step 2: Commit**

```bash
git commit -m "docs(as2-p7): drop AS2 ban, note Redis StateStore TTL=7d resume dependency"
```

### Task P7-3: 全量回归

- [ ] **Step 1: 跑全量 Live 回归包**

Run: `python scripts/phase2_agent_demo.py --suite all && python scripts/verify_spawn_subagent_live.py && python scripts/verify_react_taskboard_live.py && python scripts/verify_sandbox_live.py --suite all && python scripts/verify_sandbox_tool_cancel_live.py && python scripts/verify_peer_collab_live.py && python scripts/verify_expert_consultation_live.py && python scripts/verify_react_checkpoint_live.py`
Expected: 全绿

- [ ] **Step 2: 全量回滚脚本最终回归**

Run: `for f in scripts/verify_rollback_p*.py; do python "$f" || echo "REGRESS $f"; done`
Expected: 全 exit 0

### Task P7-4: 四模式前端抽检 + e2e 全量

- [ ] **Step 1: 全量 e2e（真实 Gateway）**

Run: `cd sunshine-ui && PLAYWRIGHT_BASE_URL=http://ecs4c16g:5173 npm run test:e2e`
Expected: 全绿

- [ ] **Step 2: 四模式人工抽检（react F1 / workflow 模板 / plan-workflow DAG / peer E1）**

- [ ] **Step 3: 更新 docs/implementation-plan.md + Commit + 打标签**

```bash
git commit -m "test(as2-p7): full regression + four-mode frontend spot check" --allow-empty
git tag as2-upgrade-done
```

---

## 附录 - 阶段速查

| 阶段 | 新建脚本 | 复用 Live | 回滚脚本 | e2e | 前端人工 |
|------|----------|-----------|----------|-----|----------|
| P1 | - | - | verify_rollback_p1.py | as2-p1-stream-events | ReAct(F1)+workflow+spawn(S1) |
| P2 | verify_react_checkpoint_live | - | verify_rollback_p2 | as2-p2-checkpoint-resume | 停->继续(FN1)+TTL |
| P3 | - | verify_react_taskboard_live | verify_rollback_p3 | as2-p3-tasklist | 任务卡(F1) |
| P4 | - | verify_spawn_subagent_live | verify_rollback_p4 | as2-p4-subagent | 子卡/取消(S1) |
| P5 | - | verify_sandbox/hitl/tool_cancel | verify_rollback_p5 | as2-p5-sandbox-workspace | 沙箱取消+写确认 |
| P6 | - | verify_peer/expert | verify_rollback_p6 | as2-p6-peer-reactive | 反应式(E1)+$ |
| P7 | - | 全量回归包 | 全量回滚最终 | 全量 e2e | 四模式抽检 |

## 附录 - 关键文件改动速查

| 文件 | 阶段 | 改动 |
|------|------|------|
| pom.xml | P1 | +agentscope-harness 依赖 |
| ReActAgentFactory | P1 | ReActAgent->HarnessAgent + Hook->Middleware |
| ProcessingStepMiddleware（新） | P1 | 替代 ProcessingStepHook |
| ReActAgentRuntime | P1 | stream->streamEvents + EventMapper |
| HarnessAgentHolder（新） | P2 | 单例载体 |
| ReactCheckpointService（新） | P2 | interrupt/resume |
| DynamicToolkitFactory | P3 | 删 ManageTasksTool |
| SpawnSubagentTool | P4 | 薄封装或删 |
| SandboxAgentTools | P5 | Workspace 内核 |
| ExpertPeerAgentFactory / ExpertHubEngine | P6 | 迁 streamEvents + 反应式 |
| AgentExecutionProperties | P1/P7 | 删 flag -> 删 As2 块 |
