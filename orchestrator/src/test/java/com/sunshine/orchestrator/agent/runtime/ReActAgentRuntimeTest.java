package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.agent.ReActAgentFactory;
import com.sunshine.orchestrator.config.AgentGroundingProperties;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.prompt.PromptComposeRequest;
import com.sunshine.orchestrator.prompt.PromptComposer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentRuntimeTest {

    @Mock
    private ReActAgentFactory agentFactory;
    @Mock
    private PromptComposer promptComposer;
    @Mock
    private ReActAgent reactAgent;
    @Mock
    private AnswerGroundingChecker groundingChecker;

    private ReActAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        AgentGroundingProperties groundingProperties = new AgentGroundingProperties();
        groundingProperties.setEnabled(false);
        runtime = new ReActAgentRuntime(agentFactory, promptComposer, groundingChecker, groundingProperties);
    }

    @Test
    void resolveBridgeId_mainUsesAssistantMessageId() {
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-main");
        assertThat(req.resolveBridgeId()).isEqualTo("msg-main");
    }

    @Test
    void resolveBridgeId_subUsesRunIdPrefix() {
        AgentRunRequest req = AgentRunRequest.sub(
                MemoryContext.empty(), "q", List.of(), "u1", "default");
        assertThat(req.resolveBridgeId()).isEqualTo("sub-" + req.runId());
    }

    @Test
    void run_plannerRoleRejected() {
        AgentRunRequest planner = new AgentRunRequest(
                AgentRole.PLANNER, "run-p", null, MemoryContext.empty(), "plan",
                List.of(), "u1", "default", null, null, null, null, 1,
                TimelineBinding.PLANNER_ONLY);
        assertThatThrownBy(() -> runtime.run(planner).collectList().block())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("PLANNER");
    }

    @Test
    void run_mainEmitsContentAndUsesAssistantBridge() {
        Msg userMsg = Msg.builder().role(MsgRole.USER).content(List.of()).build();
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of(userMsg));

        Msg resultMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("主 Agent 答复").build()))
                .build();
        Event resultEvent = new Event(EventType.AGENT_RESULT, resultMsg, false);
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "用户问题", "u1", "default", "msg-1");
        when(agentFactory.create(req)).thenReturn(reactAgent);
        when(reactAgent.stream(anyList(), any())).thenReturn(Flux.just(resultEvent));

        List<StreamToken> tokens = runtime.run(req).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(tokens.stream().anyMatch(t -> t.isContent() && "主 Agent 答复".equals(t.text()))).isTrue();

        ArgumentCaptor<PromptComposeRequest> composeCaptor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(promptComposer).composeReactInputs(composeCaptor.capture());
        assertThat(composeCaptor.getValue().userMessage()).isEqualTo("用户问题");
        assertThat(composeCaptor.getValue().skillId()).isNull();
        verify(agentFactory).create(req);
    }

    @Test
    void run_subUsesSubBridgePrefix() {
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of());

        Msg resultMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("子 Agent 结论").build()))
                .build();
        when(reactAgent.stream(anyList(), any())).thenReturn(Flux.just(
                new Event(EventType.AGENT_RESULT, resultMsg, false)));

        ArgumentCaptor<AgentRunRequest> requestCaptor = ArgumentCaptor.forClass(AgentRunRequest.class);
        when(agentFactory.create(requestCaptor.capture())).thenReturn(reactAgent);

        AgentRunRequest req = AgentRunRequest.sub(
                MemoryContext.empty(), "分析合规", List.of("制度上下文"), "u1", "default");

        List<StreamToken> tokens = runtime.run(req).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(tokens.stream().anyMatch(t -> t.isContent() && t.text().contains("子 Agent 结论"))).isTrue();
        assertThat(requestCaptor.getValue().resolveBridgeId()).isEqualTo("sub-" + req.runId());

        ArgumentCaptor<PromptComposeRequest> composeCaptor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(promptComposer).composeReactInputs(composeCaptor.capture());
        assertThat(composeCaptor.getValue().memory().stmTurns()).isEmpty();
        assertThat(composeCaptor.getValue().injectedUserContexts()).containsExactly("制度上下文");
    }

    @Test
    void run_subStripsStreamMemoryAndPassesSkillId() {
        when(promptComposer.composeReactInputs(any())).thenReturn(List.of());

        Msg resultMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(TextBlock.builder().text("ok").build()))
                .build();
        when(reactAgent.stream(anyList(), any())).thenReturn(Flux.just(
                new Event(EventType.AGENT_RESULT, resultMsg, false)));
        when(agentFactory.create(any())).thenReturn(reactAgent);

        MemoryContext fullMemory = new MemoryContext("ltm", "mtm", List.of(
                new ChatTurn("user", "历史")));
        AgentRunRequest req = AgentRunRequest.sub(
                fullMemory, "子任务", List.of("上游"), "u1", "default",
                "finance-analysis", List.of("list_finance_messages"), "overlay", 4);

        runtime.run(req).collectList().block();

        ArgumentCaptor<PromptComposeRequest> composeCaptor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(promptComposer).composeReactInputs(composeCaptor.capture());
        PromptComposeRequest composed = composeCaptor.getValue();
        assertThat(composed.memory().stmTurns()).isEmpty();
        assertThat(composed.memory().ltmSnippet()).isBlank();
        assertThat(composed.skillId()).isEqualTo("finance-analysis");
        assertThat(composed.injectedUserContexts()).containsExactly("上游");
    }
}
