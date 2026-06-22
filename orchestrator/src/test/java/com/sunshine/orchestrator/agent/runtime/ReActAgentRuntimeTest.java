package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.agent.ReActAgentFactory;
import com.sunshine.orchestrator.client.StreamToken;
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
import static org.mockito.ArgumentMatchers.eq;
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

    private ReActAgentRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new ReActAgentRuntime(agentFactory, promptComposer);
    }

    @Test
    void resolveBridgeId_mainUsesAssistantMessageId() {
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-main");
        assertThat(ReActAgentRuntime.resolveBridgeId(req)).isEqualTo("msg-main");
    }

    @Test
    void resolveBridgeId_subGeneratesIndependentBridge() {
        AgentRunRequest req = AgentRunRequest.sub(
                MemoryContext.empty(), "q", List.of(), "u1", "default");
        assertThat(ReActAgentRuntime.resolveBridgeId(req)).startsWith("sub-");
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
        when(agentFactory.create("msg-1")).thenReturn(reactAgent);
        when(reactAgent.stream(anyList(), any())).thenReturn(Flux.just(resultEvent));

        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "用户问题", "u1", "default", "msg-1");

        List<StreamToken> tokens = runtime.run(req).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(tokens.stream().anyMatch(t -> t.isContent() && "主 Agent 答复".equals(t.text()))).isTrue();

        ArgumentCaptor<PromptComposeRequest> composeCaptor = ArgumentCaptor.forClass(PromptComposeRequest.class);
        verify(promptComposer).composeReactInputs(composeCaptor.capture());
        assertThat(composeCaptor.getValue().userMessage()).isEqualTo("用户问题");
        verify(agentFactory).create(eq("msg-1"));
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

        ArgumentCaptor<String> bridgeCaptor = ArgumentCaptor.forClass(String.class);
        when(agentFactory.create(bridgeCaptor.capture())).thenReturn(reactAgent);

        AgentRunRequest req = AgentRunRequest.sub(
                MemoryContext.empty(), "分析合规", List.of("制度上下文"), "u1", "default");

        List<StreamToken> tokens = runtime.run(req).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(tokens.stream().anyMatch(t -> t.isContent() && t.text().contains("子 Agent 结论"))).isTrue();
        assertThat(bridgeCaptor.getValue()).startsWith("sub-");
    }
}
