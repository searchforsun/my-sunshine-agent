package com.sunshine.orchestrator.agent;

import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.SpawnSubagentLabels;
import com.sunshine.orchestrator.processing.SpawnSubagentLabelService;
import com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry;
import com.sunshine.orchestrator.sandbox.SandboxTimelineLabelService;
import com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AS2 P1：ProcessingStepMiddleware 单测。
 * 验证 onReasoning 入口/出口 + onActing 入口开 tool 步、出口在 ToolResultEndEvent 触发 PostActing 收口。
 */
class ProcessingStepMiddlewareTest {

    private final String bridgeId = "test-bridge";
    private final ToolCatalogService toolCatalogService = mock(ToolCatalogService.class);
    private final AgentExecutionProperties executionProperties = mock(AgentExecutionProperties.class);
    private final TaskBoardTimelineSupport taskBoardTimelineSupport = mock(TaskBoardTimelineSupport.class);
    private final SandboxTimelineLabelService sandboxTimelineLabels = mock(SandboxTimelineLabelService.class);
    private final CancellableToolRunRegistry cancellableToolRunRegistry = mock(CancellableToolRunRegistry.class);
    private final ProcessingTimelineSession session = mock(ProcessingTimelineSession.class);

    private ProcessingStepMiddleware newMiddleware() {
        return new ProcessingStepMiddleware(
                toolCatalogService, executionProperties,
                taskBoardTimelineSupport, sandboxTimelineLabels, cancellableToolRunRegistry);
    }

    /** P2-1：bridgeId 经 RuntimeContext 注入（middleware 无状态） */
    private RuntimeContext ctxWithBridge() {
        return RuntimeContext.builder()
                .userId("u1")
                .sessionId("m1")
                .put(ProcessingStepMiddleware.CTX_BRIDGE_ID, bridgeId)
                .build();
    }

    @AfterEach
    void tearDown() {
        StepEventBridge.resetRegistry();
        SpawnSubagentLabels.bind(null);
    }

    @Test
    void onReasoningOpensThinkOnEntryAndClosesOnExit() {
        StepEventBridge.bind(bridgeId, session, new ConcurrentLinkedQueue<>());
        when(executionProperties.getReact()).thenReturn(null);
        ProcessingStepMiddleware mw = newMiddleware();

        ReasoningInput input = new ReasoningInput(List.of(), List.of(), null);
        Function<ReasoningInput, Flux<AgentEvent>> next = in -> Flux.empty();

        mw.onReasoning(mock(Agent.class), ctxWithBridge(), input, next)
                .collectList().block();

        verify(session).beginReasoningRound();
        verify(session).endReasoningRound();
    }

    @Test
    void onActingOpensToolStepOnEntryAndCompletesOnToolResultEnd() {
        StepEventBridge.bind(bridgeId, session, new ConcurrentLinkedQueue<>());
        when(executionProperties.getReact()).thenReturn(null);

        String toolName = "finance__query_reimbursement";
        String toolUseId = "tu-1";
        ToolUseBlock toolUse = mock(ToolUseBlock.class);
        when(toolUse.getName()).thenReturn(toolName);
        when(toolUse.getId()).thenReturn(toolUseId);
        when(toolUse.getInput()).thenReturn(Map.of());
        when(toolCatalogService.timelineStepId(toolName)).thenReturn("tool-fin");
        when(toolCatalogService.timelinePhase(toolName)).thenReturn("tool");
        when(toolCatalogService.displayName(toolName)).thenReturn("报销查询");
        when(toolCatalogService.isRagTool(toolName)).thenReturn(false);
        when(toolCatalogService.timelineSummary(eq(toolName), any())).thenReturn("查到 3 单");
        when(sandboxTimelineLabels.isSandboxTool(toolName)).thenReturn(false);
        when(cancellableToolRunRegistry.isCancellableTool(toolName)).thenReturn(false);
        when(session.beginToolStep("tool-fin", "tool")).thenReturn("step-1");

        ProcessingStepMiddleware mw = newMiddleware();

        Function<ActingInput, Flux<AgentEvent>> next = in -> Flux.just(
                new ToolResultTextDeltaEvent("r-1", toolUseId, toolName, "命中 3 单待审批"),
                new ToolResultEndEvent("e-1", null, "r-1", toolUseId, toolName, ToolResultState.SUCCESS));

        ActingInput input = new ActingInput(List.of(toolUse));
        mw.onActing(mock(Agent.class), ctxWithBridge(), input, next)
                .collectList().block();

        // 入口：开 tool 步 + notePending
        verify(session).noteToolCallPending();
        verify(session).beginToolStep("tool-fin", "tool");
        // 出口：ToolResultEndEvent 触发 completeToolStepForToolUse + recordToolCompleted + noteToolCallDone
        verify(session).completeToolStepForToolUse(eq(toolUseId), eq("查到 3 单"), any());
        verify(session).recordToolCompleted("报销查询");
        verify(session).noteToolCallDone();
    }

    @Test
    void onActingSkipsTodoWriteToolStep() {
        StepEventBridge.bind(bridgeId, session, new ConcurrentLinkedQueue<>());
        when(executionProperties.getReact()).thenReturn(null);

        String toolUseId = "tu-todo";
        ToolUseBlock toolUse = mock(ToolUseBlock.class);
        when(toolUse.getName()).thenReturn("todo_write");
        when(toolUse.getId()).thenReturn(toolUseId);

        ProcessingStepMiddleware mw = newMiddleware();
        Function<ActingInput, Flux<AgentEvent>> next = in -> Flux.just(
                new ToolResultEndEvent("e-todo", null, "r-todo", toolUseId, "todo_write", ToolResultState.SUCCESS));

        ActingInput input = new ActingInput(List.of(toolUse));
        mw.onActing(mock(Agent.class), ctxWithBridge(), input, next)
                .collectList().block();

        // todo_write 不开 tool 步、不 recordToolCompleted（不触发「任务板工具结果综合分析」单独 think 步）
        verify(session, never()).beginToolStep(any(), any());
        verify(session, never()).noteToolCallPending();
        verify(session, never()).recordToolCompleted(any());
    }

    @Test
    void onActingSpawnSubagentRecordsCompletedWithoutToolStep() {
        StepEventBridge.bind(bridgeId, session, new ConcurrentLinkedQueue<>());
        when(executionProperties.getReact()).thenReturn(null);
        SpawnSubagentLabelService spawnLabels = mock(SpawnSubagentLabelService.class);
        when(spawnLabels.label()).thenReturn("子任务");
        SpawnSubagentLabels.bind(spawnLabels);

        String toolUseId = "tu-sp";
        ToolUseBlock toolUse = mock(ToolUseBlock.class);
        when(toolUse.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(toolUse.getId()).thenReturn(toolUseId);

        ProcessingStepMiddleware mw = newMiddleware();
        Function<ActingInput, Flux<AgentEvent>> next = in -> Flux.just(
                new ToolResultEndEvent("e-sp", null, "r-sp", toolUseId, SpawnSubagentTool.NAME, ToolResultState.SUCCESS));

        ActingInput input = new ActingInput(List.of(toolUse));
        mw.onActing(mock(Agent.class), ctxWithBridge(), input, next)
                .collectList().block();

        verify(session, never()).beginToolStep(any(), any());
        // spawn_subagent 须 recordToolCompleted（避免后续推理合并进首个 think）
        verify(session).recordToolCompleted("子任务");
    }

    @Test
    void onActingPartitionsReadWriteToolsWriteToolsSerial() {
        StepEventBridge.bind(bridgeId, session, new ConcurrentLinkedQueue<>());
        when(executionProperties.getReact()).thenReturn(null);

        String readToolA = "finance__list_expenses";
        String readToolB = "finance__summarize_expenses";
        String writeTool = "finance__submit_expense";

        ToolCatalogEntry writeEntry = new ToolCatalogEntry(
                writeTool, "提交报销", "", "sdk", "finance", null, "", "", Map.of(), "write", true, true, true, null);
        when(toolCatalogService.find(writeTool)).thenReturn(Optional.of(writeEntry));
        when(toolCatalogService.find(readToolA)).thenReturn(Optional.empty());
        when(toolCatalogService.find(readToolB)).thenReturn(Optional.empty());

        when(toolCatalogService.timelineStepId(any())).thenReturn("tool-x");
        when(toolCatalogService.timelinePhase(any())).thenReturn("tool");
        when(toolCatalogService.displayName(any())).thenReturn("工具");
        when(toolCatalogService.isRagTool(any())).thenReturn(false);
        when(toolCatalogService.timelineSummary(any(), any())).thenReturn("done");
        when(sandboxTimelineLabels.isSandboxTool(any())).thenReturn(false);
        when(cancellableToolRunRegistry.isCancellableTool(any())).thenReturn(false);
        when(session.beginToolStep(any(), any())).thenReturn("step-x");

        ToolUseBlock readA = mock(ToolUseBlock.class);
        when(readA.getName()).thenReturn(readToolA);
        when(readA.getId()).thenReturn("tu-read-a");
        when(readA.getInput()).thenReturn(Map.of());
        ToolUseBlock write = mock(ToolUseBlock.class);
        when(write.getName()).thenReturn(writeTool);
        when(write.getId()).thenReturn("tu-write");
        when(write.getInput()).thenReturn(Map.of());
        ToolUseBlock readB = mock(ToolUseBlock.class);
        when(readB.getName()).thenReturn(readToolB);
        when(readB.getId()).thenReturn("tu-read-b");
        when(readB.getInput()).thenReturn(Map.of());

        AtomicInteger nextCallCount = new AtomicInteger(0);
        Function<ActingInput, Flux<AgentEvent>> next = in -> {
            nextCallCount.incrementAndGet();
            List<AgentEvent> events = new java.util.ArrayList<>();
            for (ToolUseBlock tu : in.toolCalls()) {
                events.add(new ToolResultEndEvent(
                        "e-" + tu.getId(), null, "r-" + tu.getId(), tu.getId(), tu.getName(), ToolResultState.SUCCESS));
            }
            return Flux.fromIterable(events);
        };

        ProcessingStepMiddleware mw = newMiddleware();
        ActingInput input = new ActingInput(List.of(readA, write, readB));
        mw.onActing(mock(Agent.class), ctxWithBridge(), input, next)
                .collectList().block();

        // 读A + 写 + 读B -> 3 批（读A单独一批因后跟写，写单独一批，读B单独一批）
        // 实际：连续读归一批，写单独一批 -> 读A一批、写一批、读B一批 = 3 批
        assertThat(nextCallCount.get()).isEqualTo(3);
    }

    @Test
    void onActingAllReadToolsSingleBatch() {
        StepEventBridge.bind(bridgeId, session, new ConcurrentLinkedQueue<>());
        when(executionProperties.getReact()).thenReturn(null);

        String readToolA = "finance__list_expenses";
        String readToolB = "finance__summarize_expenses";

        when(toolCatalogService.find(readToolA)).thenReturn(Optional.empty());
        when(toolCatalogService.find(readToolB)).thenReturn(Optional.empty());
        when(toolCatalogService.timelineStepId(any())).thenReturn("tool-x");
        when(toolCatalogService.timelinePhase(any())).thenReturn("tool");
        when(toolCatalogService.displayName(any())).thenReturn("工具");
        when(toolCatalogService.isRagTool(any())).thenReturn(false);
        when(toolCatalogService.timelineSummary(any(), any())).thenReturn("done");
        when(sandboxTimelineLabels.isSandboxTool(any())).thenReturn(false);
        when(cancellableToolRunRegistry.isCancellableTool(any())).thenReturn(false);
        when(session.beginToolStep(any(), any())).thenReturn("step-x");

        ToolUseBlock readA = mock(ToolUseBlock.class);
        when(readA.getName()).thenReturn(readToolA);
        when(readA.getId()).thenReturn("tu-read-a");
        when(readA.getInput()).thenReturn(Map.of());
        ToolUseBlock readB = mock(ToolUseBlock.class);
        when(readB.getName()).thenReturn(readToolB);
        when(readB.getId()).thenReturn("tu-read-b");
        when(readB.getInput()).thenReturn(Map.of());

        AtomicInteger nextCallCount = new AtomicInteger(0);
        Function<ActingInput, Flux<AgentEvent>> next = in -> {
            nextCallCount.incrementAndGet();
            List<AgentEvent> events = new java.util.ArrayList<>();
            for (ToolUseBlock tu : in.toolCalls()) {
                events.add(new ToolResultEndEvent(
                        "e-" + tu.getId(), null, "r-" + tu.getId(), tu.getId(), tu.getName(), ToolResultState.SUCCESS));
            }
            return Flux.fromIterable(events);
        };

        ProcessingStepMiddleware mw = newMiddleware();
        ActingInput input = new ActingInput(List.of(readA, readB));
        mw.onActing(mock(Agent.class), ctxWithBridge(), input, next)
                .collectList().block();

        // 全读 -> 单批（不拆分）
        assertThat(nextCallCount.get()).isEqualTo(1);
    }

    @Test
    void onActingSandboxExecRunsParallelWithReadTools() {
        StepEventBridge.bind(bridgeId, session, new ConcurrentLinkedQueue<>());
        when(executionProperties.getReact()).thenReturn(null);

        String readTool = "finance__list_expenses";
        String execTool = "sandbox__exec";

        when(toolCatalogService.find(readTool)).thenReturn(Optional.empty());
        when(toolCatalogService.find(execTool)).thenReturn(Optional.empty());
        when(toolCatalogService.timelineStepId(any())).thenReturn("tool-x");
        when(toolCatalogService.timelinePhase(any())).thenReturn("tool");
        when(toolCatalogService.displayName(any())).thenReturn("工具");
        when(toolCatalogService.isRagTool(any())).thenReturn(false);
        when(toolCatalogService.timelineSummary(any(), any())).thenReturn("done");
        when(sandboxTimelineLabels.isSandboxTool(any())).thenReturn(false);
        when(cancellableToolRunRegistry.isCancellableTool(any())).thenReturn(false);
        when(session.beginToolStep(any(), any())).thenReturn("step-x");

        ToolUseBlock read = mock(ToolUseBlock.class);
        when(read.getName()).thenReturn(readTool);
        when(read.getId()).thenReturn("tu-read");
        when(read.getInput()).thenReturn(Map.of());
        ToolUseBlock exec = mock(ToolUseBlock.class);
        when(exec.getName()).thenReturn(execTool);
        when(exec.getId()).thenReturn("tu-exec");
        when(exec.getInput()).thenReturn(Map.of());

        AtomicInteger nextCallCount = new AtomicInteger(0);
        Function<ActingInput, Flux<AgentEvent>> next = in -> {
            nextCallCount.incrementAndGet();
            List<AgentEvent> events = new java.util.ArrayList<>();
            for (ToolUseBlock tu : in.toolCalls()) {
                events.add(new ToolResultEndEvent(
                        "e-" + tu.getId(), null, "r-" + tu.getId(), tu.getId(), tu.getName(), ToolResultState.SUCCESS));
            }
            return Flux.fromIterable(events);
        };

        ProcessingStepMiddleware mw = newMiddleware();
        ActingInput input = new ActingInput(List.of(read, exec));
        mw.onActing(mock(Agent.class), ctxWithBridge(), input, next)
                .collectList().block();

        // sandbox__exec 不加写锁（避免长任务阻塞会话）-> 与读工具并行 = 单批
        assertThat(nextCallCount.get()).isEqualTo(1);
    }
}
