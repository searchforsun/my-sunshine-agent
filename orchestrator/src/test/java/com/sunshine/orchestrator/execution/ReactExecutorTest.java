package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReactExecutorTest {

    @Mock
    private AgentRuntime agentRuntime;

    @InjectMocks
    private ReactExecutor reactExecutor;

    @Test
    void execute_passesSkillIdFromPlan() {
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "@finance-analysis 是否合规", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.REACT, null,
                        Map.of(
                                SkillBindingOutcome.PARAM_SKILL, "finance-analysis",
                                SkillBindingOutcome.PARAM_EFFECTIVE_QUERY, "是否合规"),
                        "skill:@mention"));

        reactExecutor.execute(ctx).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.skillId()).isEqualTo("finance-analysis");
        assertThat(req.query()).isEqualTo("是否合规");
    }

    @Test
    void execute_buildsMainAgentRunRequest() {
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "查财务待审批", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "test"));

        List<StreamToken> tokens = reactExecutor.execute(ctx).collectList().block();
        assertThat(tokens).isNotNull();
        assertThat(tokens.stream().anyMatch(StreamToken::isContent)).isTrue();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.role()).isEqualTo(AgentRole.MAIN);
        assertThat(req.query()).isEqualTo("查财务待审批");
        assertThat(req.assistantMessageId()).isEqualTo("msg-1");
        assertThat(req.userId()).isEqualTo("u1");
    }

    @Test
    void execute_passesPersonalRulesAsFirstInjectedBlock() {
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "你好", AssembledContext.empty(),
                null, null, "u1", "default", null,
                new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "test"),
                null, null, null, false, false, null, "用文言文回答");

        reactExecutor.execute(ctx).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().injectedBlocks())
                .containsExactly("## 用户个人规则\n用文言文回答");
    }

    @Test
    void execute_personalRulesBeforeDegradedInjectedBlocks() {
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "你好", AssembledContext.empty(),
                null, null, "u1", "default", null,
                new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "test"),
                null, null, null, false, false, null, "用文言文回答");

        reactExecutor.executeWithInjected(ctx, List.of("上游节点输出")).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().injectedBlocks())
                .containsExactly("## 用户个人规则\n用文言文回答", "上游节点输出");
    }

    @Test
    void execute_withoutPersonalRulesKeepsInjectedBlocksUntouched() {
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "你好", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.REACT, null, Map.of(), "test"));

        reactExecutor.executeWithInjected(ctx, List.of("上游节点输出")).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().injectedBlocks()).containsExactly("上游节点输出");
    }
}
