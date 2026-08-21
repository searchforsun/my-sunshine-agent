package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.ReactExecutor;
import com.sunshine.orchestrator.processing.TimelineLabelTestSupport;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlannerHarnessExecutorTest {

    @Mock
    private PlanNotebookStore store;
    @Mock
    private PlannerHarnessLoop loop;
    @Mock
    private ReactExecutor reactExecutor;

    private AgentExecutionProperties executionProperties;
    private PlannerHarnessExecutor executor;

    @BeforeEach
    void setUp() {
        TimelineLabelTestSupport.bindDefaults();
        executionProperties = new AgentExecutionProperties();
        executionProperties.getHarness().setEnabled(true);
        executionProperties.getHarness().setMaxRounds(12);
        executionProperties.getHarness().setMaxTotalTasks(24);
        executor = new PlannerHarnessExecutor(store, loop, reactExecutor, executionProperties);
    }

    @AfterEach
    void tearDown() {
        TimelineLabelTestSupport.unbind();
    }

    @Test
    void execute_createsNotebookWhenMissing_andRenewsTtl() {
        when(store.load("conv-1")).thenReturn(Optional.empty());
        when(loop.run(any(), any())).thenReturn(Flux.just(StreamToken.content("ok")));

        List<StreamToken> tokens = executor.execute(ctx("conv-1", "msg-1")).collectList().block();

        assertThat(tokens).extracting(StreamToken::text).containsExactly("ok");
        ArgumentCaptor<PlanNotebook> notebookCaptor = ArgumentCaptor.forClass(PlanNotebook.class);
        verify(loop).run(any(), notebookCaptor.capture());
        PlanNotebook notebook = notebookCaptor.getValue();
        assertThat(notebook.getSessionId()).isEqualTo("conv-1");
        assertThat(notebook.getOriginalGoal()).isEqualTo("分两步规划");
        assertThat(notebook.getMaxRounds()).isEqualTo(12);
        assertThat(notebook.getMaxTotalTasks()).isEqualTo(24);
        verify(store).renewTtl("conv-1");
    }

    @Test
    void execute_usesAssistantMsgIdWhenConversationIdBlank() {
        when(store.load("msg-1")).thenReturn(Optional.empty());
        when(loop.run(any(), any())).thenReturn(Flux.just(StreamToken.content("ok")));

        executor.execute(ctx(null, "msg-1")).collectList().block();

        ArgumentCaptor<PlanNotebook> notebookCaptor = ArgumentCaptor.forClass(PlanNotebook.class);
        verify(loop).run(any(), notebookCaptor.capture());
        assertThat(notebookCaptor.getValue().getSessionId()).isEqualTo("msg-1");
        verify(store).renewTtl("msg-1");
    }

    @Test
    void execute_reusesLoadedNotebook() {
        PlanNotebook existing = PlanNotebook.create("分两步规划", "分两步规划", null, 8, 16);
        existing.setSessionId("conv-1");
        when(store.load("conv-1")).thenReturn(Optional.of(existing));
        when(loop.run(any(), eq(existing))).thenReturn(Flux.just(StreamToken.content("resume")));

        executor.execute(ctx("conv-1", "msg-1")).collectList().block();

        verify(loop).run(any(), eq(existing));
        verify(store).renewTtl("conv-1");
        assertThat(existing.getOriginalGoal()).isEqualTo("分两步规划");
    }

    @Test
    void followUpQueryMarksPendingObsoleteAndUpdatesGoal() {
        PlanNotebook existing = PlanNotebook.create("旧目标", "旧目标", "chat", 12, 24);
        existing.setSessionId("c1");
        existing.getTaskQueue().add(TaskItem.initial("t1", "A", List.of(), null, null, null).withStatus("done", null));
        existing.getTaskQueue().add(TaskItem.initial("t2", "B", List.of(), null, null, null));
        when(store.load("c1")).thenReturn(Optional.of(existing));
        when(loop.run(any(), eq(existing))).thenReturn(Flux.just(StreamToken.content("ok")));

        executor.execute(ctxWithQuery("c1", "msg-1", "新目标：竞品改为 B 公司")).collectList().block();

        assertThat(existing.getOriginalGoal()).contains("新目标");
        assertThat(findStatus(existing, "t1")).isEqualTo("done");
        assertThat(findStatus(existing, "t2")).isEqualTo("obsolete");
        assertThat(existing.getReplanCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void execute_bindsToolAuditContextForSpawn() {
        when(store.load("conv-1")).thenReturn(Optional.empty());
        when(loop.run(any(), any())).thenReturn(Flux.just(StreamToken.content("ok")));

        executor.execute(ctx("conv-1", "msg-1")).collectList().block();

        // PRO 路径缺审计绑定会直接拒绝 worker/planner 的 spawn_subagent（平台拦截「缺少会话审计上下文」）
        StepEventBridge.ToolAuditContext audit = StepEventBridge.toolAuditContext("msg-1");
        assertThat(audit).isNotNull();
        assertThat(audit.conversationId()).isEqualTo("conv-1");
        assertThat(audit.messageId()).isEqualTo("msg-1");
        assertThat(audit.userId()).isEqualTo("u1");
        assertThat(audit.tenantId()).isEqualTo("default");
    }

    @Test
    void execute_onTerminalError_fallsBackToReactWhenEnabled() {
        when(store.load("conv-1")).thenReturn(Optional.empty());
        when(loop.run(any(), any())).thenReturn(Flux.error(new IllegalStateException("boom")));
        when(reactExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("react-fallback")));

        List<StreamToken> tokens = executor.execute(ctx("conv-1", "msg-1")).collectList().block();

        assertThat(tokens).extracting(StreamToken::text).contains("react-fallback");
        verify(reactExecutor).execute(any());
        verify(store).renewTtl("conv-1");
    }

    @Test
    void execute_onTerminalError_propagatesWhenFallbackDisabled() {
        executionProperties.getHarness().getFallbackReact().setEnabled(false);
        when(store.load("conv-1")).thenReturn(Optional.empty());
        when(loop.run(any(), any())).thenReturn(Flux.error(new IllegalStateException("boom")));

        Throwable error = null;
        try {
            executor.execute(ctx("conv-1", "msg-1")).collectList().block();
        } catch (Throwable t) {
            error = t;
        }

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).contains("boom");
        verify(reactExecutor, never()).execute(any());
        verify(store).renewTtl("conv-1");
    }

    private static ExecutionStreamContext ctx(String conversationId, String assistantMsgId) {
        return ctxWithQuery(conversationId, assistantMsgId, "分两步规划");
    }

    private static ExecutionStreamContext ctxWithQuery(
            String conversationId, String assistantMsgId, String query) {
        return new ExecutionStreamContext(
                conversationId,
                assistantMsgId,
                query,
                AssembledContext.empty(),
                null,
                null,
                "u1",
                "default",
                new ExecutionPlan(ExecutionMode.PRO, null, Map.of(), "test"));
    }

    private static String findStatus(PlanNotebook nb, String taskId) {
        for (TaskItem item : nb.getTaskQueue()) {
            if (taskId.equals(item.taskId())) {
                return item.status();
            }
        }
        return null;
    }
}
