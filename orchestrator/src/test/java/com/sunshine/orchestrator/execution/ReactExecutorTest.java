package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.biz.BusinessContextAssembler;
import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.context.ContextAssembler;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReactExecutorTest {

    @Mock
    private AgentRuntime agentRuntime;

    @Mock
    private AgentCatalogService agentCatalogService;

    @Mock
    private AgentExecutionProperties executionProperties;

    @Mock
    private PromptCatalogHolder catalogHolder;

    @Mock
    private BusinessContextAssembler businessContextAssembler;

    @Mock
    private ContextAssembler contextAssembler;

    @InjectMocks
    private ReactExecutor reactExecutor;

    @BeforeEach
    void setUp() {
        // M0：attachL3 默认原样返回 memory（测试不关心 L3 装配细节）
        lenient().when(contextAssembler.attachL3(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AgentExecutionProperties.React reactStub() {
        AgentExecutionProperties.React react = new AgentExecutionProperties.React();
        react.setTaskMaxIters(100);
        return react;
    }

    private void stubReact() {
        when(executionProperties.getReact()).thenReturn(reactStub());
    }

    @Test
    void execute_passesSkillIdFromPlan() {
        stubReact();
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "/finance-analysis 是否合规", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.FAST, null,
                        Map.of(
                                SkillBindingOutcome.PARAM_SKILL, "finance-analysis",
                                SkillBindingOutcome.PARAM_EFFECTIVE_QUERY, "是否合规"),
                        "skill:/mention"));

        reactExecutor.execute(ctx).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.skillId()).isEqualTo("finance-analysis");
        assertThat(req.query()).isEqualTo("是否合规");
        assertThat(req.triggeredSkillIds()).containsExactly("finance-analysis");
    }

    @Test
    void execute_buildsMainAgentRunRequest() {
        stubReact();
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "查财务待审批", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "test"));

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
    void execute_taskConversation_injectsTaskMaxIters() {
        stubReact();
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "执行沙箱任务", AssembledContext.empty(),
                null, null, "u1", "default", null,
                new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "test"),
                null, null, null, false, false, null, null, "task");

        reactExecutor.execute(ctx).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().maxIters()).isEqualTo(100);
    }

    @Test
    void execute_chatConversation_usesNacosDefaultMaxIters() {
        stubReact();
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "普通聊天", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "test"));

        reactExecutor.execute(ctx).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().maxIters()).isEqualTo(0);
    }

    @Test
    void execute_passesPersonalRulesAsFirstInjectedBlock() {
        stubReact();
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "你好", AssembledContext.empty(),
                null, null, "u1", "default", null,
                new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "test"),
                null, null, null, false, false, null, "用文言文回答", null);

        reactExecutor.execute(ctx).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().injectedBlocks())
                .containsExactly("## 用户个人规则\n用文言文回答");
    }

    @Test
    void execute_personalRulesBeforeDegradedInjectedBlocks() {
        stubReact();
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "你好", AssembledContext.empty(),
                null, null, "u1", "default", null,
                new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "test"),
                null, null, null, false, false, null, "用文言文回答", null);

        reactExecutor.executeWithInjected(ctx, List.of("上游节点输出")).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().injectedBlocks())
                .containsExactly("## 用户个人规则\n用文言文回答", "上游节点输出");
    }

    @Test
    void execute_withoutPersonalRulesKeepsInjectedBlocksUntouched() {
        stubReact();
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "你好", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "test"));

        reactExecutor.executeWithInjected(ctx, List.of("上游节点输出")).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().injectedBlocks()).containsExactly("上游节点输出");
    }

    @Test
    void execute_passesMultiValueTriggeredSkillIdsFromClassifier() {
        // S-T：classifier 多值 skillIds 全部进触发集，skillId 保留首项兼容单数语义
        stubReact();
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "对比合规", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.FAST, null,
                        Map.of(
                                "skillIds", "compliance-review,policy-qa",
                                SkillBindingOutcome.PARAM_SKILL, "compliance-review"),
                        "l3"));

        reactExecutor.execute(ctx).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        AgentRunRequest req = captor.getValue();
        assertThat(req.triggeredSkillIds()).containsExactly("compliance-review", "policy-qa");
        assertThat(req.skillId()).isEqualTo("compliance-review");
    }

    @Test
    void execute_withoutAnySkill_triggeredSkillIdsEmpty() {
        stubReact();
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("ok")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "msg-1", "你好", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "test"));

        reactExecutor.execute(ctx).collectList().block();

        ArgumentCaptor<AgentRunRequest> captor = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(agentRuntime).run(captor.capture());
        assertThat(captor.getValue().triggeredSkillIds()).isEmpty();
        assertThat(captor.getValue().skillId()).isNull();
    }
}
