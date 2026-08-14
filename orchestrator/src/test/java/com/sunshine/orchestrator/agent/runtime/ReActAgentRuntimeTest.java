package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.agent.DecisionResumeSupport;
import com.sunshine.orchestrator.agent.HarnessAgentHolder;
import com.sunshine.orchestrator.agent.SpawnRunRegistry;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentGroundingProperties;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import com.sunshine.orchestrator.taskboard.ReactTaskBoardService;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import com.sunshine.orchestrator.sandbox.SandboxSessionLifecycle;
import com.sunshine.orchestrator.sandbox.SandboxWriteEditPlaceholderSupport;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentRuntimeTest {

    @Mock
    private HarnessAgentHolder agentHolder;
    @Mock
    private PromptComposer promptComposer;
    @Mock
    private HarnessAgent reactAgent;
    @Mock
    private AnswerGroundingChecker groundingChecker;
    @Mock
    private ReactTaskBoardService taskBoardService;
    @Mock
    private SandboxSessionLifecycle sandboxSessionLifecycle;
    @Mock
    private com.sunshine.orchestrator.conversation.repo.ChatConversationRepository conversationRepo;
    @Mock
    private ObjectProvider<SpawnRunRegistry> spawnRunRegistry;
    @Mock
    private ObjectProvider<DecisionResumeSupport> decisionResumeSupport;
    @Mock
    private SandboxWriteEditPlaceholderSupport writeEditPlaceholder;

    private ReActAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        AgentGroundingProperties groundingProperties = new AgentGroundingProperties();
        groundingProperties.setEnabled(false);
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();
        lenient().when(spawnRunRegistry.getIfAvailable()).thenReturn(null);
        lenient().when(decisionResumeSupport.getIfAvailable()).thenReturn(null);
        runtime = new ReActAgentRuntime(
                agentHolder, promptComposer, groundingChecker, groundingProperties,
                taskBoardService, executionProperties, sandboxSessionLifecycle,
                conversationRepo, spawnRunRegistry, decisionResumeSupport, writeEditPlaceholder);
    }

    @Test
    void resolveBridgeId_mainUsesRunIdPrefix() {
        AgentRunRequest req = AgentRunRequest.main(
                AssembledContext.empty(), "q", "u1", "default", "msg-main");
        assertThat(req.resolveBridgeId()).isEqualTo("main-" + req.runId());
    }

    @Test
    void resolveBridgeId_subUsesRunIdPrefix() {
        AgentRunRequest req = AgentRunRequest.sub(
                AssembledContext.empty(), "q", List.of(), "u1", "default");
        assertThat(req.resolveBridgeId()).isEqualTo("sub-" + req.runId());
    }

    @Test
    void run_plannerRoleRejected() {
        AgentRunRequest planner = new AgentRunRequest(
                AgentRole.PLANNER, "run-p", null, AssembledContext.empty(), "plan",
                List.of(), "u1", "default", null, null, null, null, 1,
                TimelineBinding.PLANNER_ONLY, false, null, null, 0, null, null, null, null, null, null);
        assertThatThrownBy(() -> runtime.run(planner).collectList().block())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("PLANNER");
        verify(sandboxSessionLifecycle, never()).prepareRun(any());
    }

    @Test
    void runPlannerReAct_acceptsPlannerWithoutDecisionResume() {
        Msg userMsg = Msg.builder().role(MsgRole.USER).content(List.of()).build();
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of(userMsg));
        AgentRunRequest req = AgentRunRequest.planner("plan next", "u1", "default", "msg-p");
        when(agentHolder.get(req)).thenReturn(reactAgent);
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.<io.agentscope.core.event.AgentEvent>empty());

        List<StreamToken> tokens = runtime.runPlannerReAct(req).collectList().block();

        assertThat(tokens).isNotNull();
        ArgumentCaptor<PromptComposeRequest> composeCaptor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(promptComposer).composeReactInputs(composeCaptor.capture());
        assertThat(composeCaptor.getValue().harnessPromptId()).isEqualTo("planner.harness");
        verify(sandboxSessionLifecycle).prepareRun(req);
        verify(sandboxSessionLifecycle).closeQuietly(req);
    }

    @Test
    void run_workerAcceptedAndKeepsForWorkerMemory() {
        Msg userMsg = Msg.builder().role(MsgRole.USER).content(List.of()).build();
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of(userMsg));
        AgentRunRequest req = AgentRunRequest.worker(
                AssembledContext.forWorker("STABLE", ""),
                "do task", List.of("sandbox__exec"), "u1", "default", "a1", "c1", 100, "parent");
        when(agentHolder.get(req)).thenReturn(reactAgent);
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.<io.agentscope.core.event.AgentEvent>empty());
        List<StreamToken> tokens = runtime.run(req).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(req.resolveBridgeId()).startsWith("worker-");
        ArgumentCaptor<PromptComposeRequest> composeCaptor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(promptComposer).composeReactInputs(composeCaptor.capture());
        assertThat(composeCaptor.getValue().context().projectGuideBlock()).isEqualTo("STABLE");
        verify(sandboxSessionLifecycle).prepareRun(req);
        verify(sandboxSessionLifecycle).closeQuietly(req);
    }

    @Test
    void run_mainEmitsContentAndUsesAssistantBridge() {
        Msg userMsg = Msg.builder().role(MsgRole.USER).content(List.of()).build();
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of(userMsg));

        AgentRunRequest req = AgentRunRequest.main(
                AssembledContext.empty(), "用户问题", "u1", "default", "msg-1");
        when(agentHolder.get(req)).thenReturn(reactAgent);
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.<io.agentscope.core.event.AgentEvent>empty());

        List<StreamToken> tokens = runtime.run(req).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(req.resolveBridgeId()).startsWith("main-");

        ArgumentCaptor<PromptComposeRequest> composeCaptor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(promptComposer).composeReactInputs(composeCaptor.capture());
        assertThat(composeCaptor.getValue().userMessage()).isEqualTo("用户问题");
        assertThat(composeCaptor.getValue().skillId()).isNull();
        verify(agentHolder).get(req);
        verify(sandboxSessionLifecycle).prepareRun(req);
        verify(sandboxSessionLifecycle).closeQuietly(req);
    }

    @Test
    void run_createsAgentOnDedicatedScheduler_evenWhenSubscribedFromParallel() {
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of());
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.<io.agentscope.core.event.AgentEvent>empty());
        java.util.concurrent.atomic.AtomicReference<String> createThread = new java.util.concurrent.atomic.AtomicReference<>();
        when(agentHolder.get(any())).thenAnswer(inv -> {
            createThread.set(Thread.currentThread().getName());
            return reactAgent;
        });
        AgentRunRequest req = AgentRunRequest.main(
                AssembledContext.empty(), "待审批是否合规", "u1", "default", "msg-nb");
        runtime.run(req)
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
                .collectList()
                .block();
        assertThat(createThread.get()).isNotNull();
        // run 须在专用调度（虚拟线程）上执行阻塞组装，禁止落在 parallel/reactor-http 线程
        assertThat(createThread.get()).doesNotContainIgnoringCase("parallel");
    }

    @Test
    void run_subUsesSubBridgePrefix() {
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of());
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.<io.agentscope.core.event.AgentEvent>empty());

        ArgumentCaptor<AgentRunRequest> requestCaptor = ArgumentCaptor.forClass(AgentRunRequest.class);
        when(agentHolder.get(requestCaptor.capture())).thenReturn(reactAgent);

        AgentRunRequest req = AgentRunRequest.sub(
                AssembledContext.empty(), "分析合规", List.of("制度上下文"), "u1", "default");

        List<StreamToken> tokens = runtime.run(req).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(requestCaptor.getValue().resolveBridgeId()).isEqualTo("sub-" + req.runId());

        ArgumentCaptor<PromptComposeRequest> composeCaptor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(promptComposer).composeReactInputs(composeCaptor.capture());
        assertThat(composeCaptor.getValue().context().nearTurns()).isEmpty();
        assertThat(composeCaptor.getValue().injectedUserContexts()).containsExactly("制度上下文");
        verify(sandboxSessionLifecycle).prepareRun(req);
        verify(sandboxSessionLifecycle).closeQuietly(req);
    }

    @Test
    void run_subStripsStreamMemoryAndPassesSkillId() {
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of());
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.<io.agentscope.core.event.AgentEvent>empty());
        when(agentHolder.get(any())).thenReturn(reactAgent);

        AssembledContext fullMemory = new AssembledContext("ltm", "mtm", List.of(), List.of(
                new ChatTurn("user", "历史")), "");
        AgentRunRequest req = AgentRunRequest.sub(
                fullMemory, "子任务", List.of("上游"), "u1", "default",
                null, "finance-analysis", List.of("sdk__sunshine-finance__list_my_expenses"), "overlay", 4);

        runtime.run(req).collectList().block();

        ArgumentCaptor<PromptComposeRequest> composeCaptor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(promptComposer).composeReactInputs(composeCaptor.capture());
        PromptComposeRequest composed = composeCaptor.getValue();
        assertThat(composed.context().nearTurns()).isEmpty();
        assertThat(composed.context().l2SystemBlock()).isBlank();
        assertThat(composed.skillId()).isEqualTo("finance-analysis");
        assertThat(composed.injectedUserContexts()).containsExactly("上游");
    }

    @Test
    void run_mainPreparesSandboxContextAndClosesOnce() {
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of());
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.<io.agentscope.core.event.AgentEvent>empty());
        when(agentHolder.get(any())).thenReturn(reactAgent);

        AgentRunRequest req = AgentRunRequest.main(
                AssembledContext.empty(), "读文件", "u1", "default", "msg-s", List.of(), "coding-skill");

        runtime.run(req).collectList().block();

        verify(sandboxSessionLifecycle, times(1)).prepareRun(req);
        verify(sandboxSessionLifecycle, times(1)).closeQuietly(req);
    }

    @Test
    void run_errorStillClosesSandboxSession() {
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of());
        when(agentHolder.get(any())).thenReturn(reactAgent);
        when(reactAgent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(Flux.<io.agentscope.core.event.AgentEvent>error(new RuntimeException("boom")));

        AgentRunRequest req = AgentRunRequest.main(
                AssembledContext.empty(), "q", "u1", "default", "msg-err", List.of(), "coding-skill");

        assertThatThrownBy(() -> runtime.run(req).collectList().block())
                .hasMessageContaining("boom");

        verify(sandboxSessionLifecycle).prepareRun(req);
        verify(sandboxSessionLifecycle).closeQuietly(req);
    }
}
