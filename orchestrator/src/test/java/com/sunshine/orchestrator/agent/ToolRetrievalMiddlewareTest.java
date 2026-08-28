package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.ToolContextState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 5.5 工具语义检索注入中间件单测：
 * 开关/MAIN-only/检索激活组写入/失败与空结果回退/query 提取（最近 USER）。
 */
class ToolRetrievalMiddlewareTest {

    private AgentExecutionProperties props(String mode, int topK, boolean fallbackFull) {
        AgentExecutionProperties.React.ToolInject inject = new AgentExecutionProperties.React.ToolInject();
        inject.setMode(mode);
        inject.setTopK(topK);
        inject.setFallbackFull(fallbackFull);
        AgentExecutionProperties.React react = new AgentExecutionProperties.React();
        react.setToolInject(inject);
        AgentExecutionProperties p = new AgentExecutionProperties();
        p.setReact(react);
        return p;
    }

    private ToolRetrievalMiddleware middleware(
            AgentExecutionProperties props, ToolRetrievalService service) {
        return new ToolRetrievalMiddleware(service, props);
    }

    private RuntimeContext ctx(AgentRole role, String tenantId) {
        RuntimeContext rt = mock(RuntimeContext.class);
        when(rt.get(ProcessingStepMiddleware.CTX_AGENT_ROLE)).thenReturn(role);
        when(rt.get(ToolRetrievalMiddleware.CTX_TENANT_ID)).thenReturn(tenantId);
        when(rt.get(ToolRetrievalMiddleware.CTX_CONVERSATION_KIND)).thenReturn("chat");
        return rt;
    }

    private AgentState agentState() {
        AgentState state = mock(AgentState.class);
        when(state.getToolContext()).thenReturn(ToolContextState.builder().build());
        return state;
    }

    private void runReasoning(ToolRetrievalMiddleware mw, RuntimeContext rt, List<Msg> messages) {
        ReasoningInput input = new ReasoningInput(messages, List.of(), null);
        Function<ReasoningInput, Flux<AgentEvent>> next = in -> Flux.empty();
        mw.onReasoning(mock(Agent.class), rt, input, next).collectList().block();
    }

    private List<Msg> userMsg(String text) {
        return List.of(Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build());
    }

    @Test
    void disabled_doesNotTouchActivatedGroups() {
        ToolRetrievalService svc = mock(ToolRetrievalService.class);
        when(svc.retrievalEnabled()).thenReturn(false);
        ToolRetrievalMiddleware mw = middleware(props("full", 8, true), svc);
        RuntimeContext rt = ctx(AgentRole.MAIN, "t1");
        AgentState state = agentState();
        when(rt.getAgentState()).thenReturn(state);
        runReasoning(mw, rt, userMsg("报销"));
        // 真实 ToolContextState 初始无激活组；middleware 未触碰则保持空
        assertThat(state.getToolContext().getActivatedGroups()).isEmpty();
    }

    @Test
    void subRole_doesNotTouchActivatedGroups() {
        ToolRetrievalService svc = mock(ToolRetrievalService.class);
        when(svc.retrievalEnabled()).thenReturn(true);
        ToolRetrievalMiddleware mw = middleware(props("retrieval", 8, true), svc);
        RuntimeContext rt = ctx(AgentRole.SUB, "t1");
        AgentState state = agentState();
        when(rt.getAgentState()).thenReturn(state);
        runReasoning(mw, rt, userMsg("报销"));
        assertThat(state.getToolContext().getActivatedGroups()).isEmpty();
    }

    @Test
    void searchHits_setActivatedGroupsPerTopK() {
        ToolRetrievalService svc = mock(ToolRetrievalService.class);
        when(svc.retrievalEnabled()).thenReturn(true);
        when(svc.searchToolIds("报销", "t1", "chat", 8))
                .thenReturn(List.of("finance__expense", "oa__leave"));
        ToolRetrievalMiddleware mw = middleware(props("retrieval", 8, true), svc);
        RuntimeContext rt = ctx(AgentRole.MAIN, "t1");
        AgentState state = agentState();
        when(rt.getAgentState()).thenReturn(state);
        runReasoning(mw, rt, userMsg("报销"));
        assertThat(state.getToolContext().getActivatedGroups())
                .containsExactly("tool:finance__expense", "tool:oa__leave");
    }

    @Test
    void retrievalThrows_fallsBackToAllSearchable() {
        ToolRetrievalService svc = mock(ToolRetrievalService.class);
        when(svc.retrievalEnabled()).thenReturn(true);
        when(svc.searchToolIds("报销", "t1", "chat", 8)).thenThrow(new RuntimeException("rag down"));
        when(svc.fallbackToolIds("t1", "chat")).thenReturn(List.of("finance__expense"));
        ToolRetrievalMiddleware mw = middleware(props("retrieval", 8, true), svc);
        RuntimeContext rt = ctx(AgentRole.MAIN, "t1");
        AgentState state = agentState();
        when(rt.getAgentState()).thenReturn(state);
        runReasoning(mw, rt, userMsg("报销"));
        assertThat(state.getToolContext().getActivatedGroups()).containsExactly("tool:finance__expense");
    }

    @Test
    void emptyHits_withFallbackFull_activatesAllSearchable() {
        ToolRetrievalService svc = mock(ToolRetrievalService.class);
        when(svc.retrievalEnabled()).thenReturn(true);
        when(svc.searchToolIds("报销", "t1", "chat", 8)).thenReturn(List.of());
        when(svc.fallbackToolIds("t1", "chat")).thenReturn(List.of("finance__expense", "oa__leave"));
        ToolRetrievalMiddleware mw = middleware(props("retrieval", 8, true), svc);
        RuntimeContext rt = ctx(AgentRole.MAIN, "t1");
        AgentState state = agentState();
        when(rt.getAgentState()).thenReturn(state);
        runReasoning(mw, rt, userMsg("报销"));
        assertThat(state.getToolContext().getActivatedGroups())
                .containsExactly("tool:finance__expense", "tool:oa__leave");
    }

    @Test
    void emptyHits_withoutFallback_activatesNothing() {
        ToolRetrievalService svc = mock(ToolRetrievalService.class);
        when(svc.retrievalEnabled()).thenReturn(true);
        when(svc.searchToolIds("报销", "t1", "chat", 8)).thenReturn(List.of());
        ToolRetrievalMiddleware mw = middleware(props("retrieval", 8, false), svc);
        RuntimeContext rt = ctx(AgentRole.MAIN, "t1");
        AgentState state = agentState();
        when(rt.getAgentState()).thenReturn(state);
        runReasoning(mw, rt, userMsg("报销"));
        assertThat(state.getToolContext().getActivatedGroups()).isEmpty();
    }

    @Test
    void extractQuery_usesLatestUserMessage_truncates() {
        List<Msg> messages = List.of(
                userMsg("first").get(0),
                Msg.builder().role(MsgRole.ASSISTANT).content(TextBlock.builder().text("ok").build()).build(),
                userMsg("last query").get(0));
        assertThat(ToolRetrievalMiddleware.extractQuery(messages)).isEqualTo("last query");

        String longText = "a".repeat(500);
        assertThat(ToolRetrievalMiddleware.extractQuery(userMsg(longText)))
                .hasSize(400)
                .isEqualTo("a".repeat(400));
    }
}
