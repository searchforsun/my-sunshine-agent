package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.TaskContextState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 4.7.7 GoalAlignmentMiddleware 单测（spec §9.1）：
 * 开关/MAIN-only/建板闸门/轮次间隔/工具闸门/SYNTHETIC 注入内容。
 */
class GoalAlignmentMiddlewareTest {

    private final String bridgeId = "gam-bridge";
    private static final String GOAL_TEMPLATE =
            "<system-reminder>\n【目标对齐检查】原始任务：{userQuery}\n当前进度：{taskProgress}\n若发现方向偏离，请用 todo_write 修正任务清单再继续。\n</system-reminder>";

    @AfterEach
    void resetBridge() {
        StepEventBridge.resetRegistry();
    }

    private GoalAlignmentMiddleware newMiddleware(boolean enabled, int everyNThink) {
        AgentExecutionProperties.React react = new AgentExecutionProperties.React();
        AgentExecutionProperties.React.GoalCheck cfg = new AgentExecutionProperties.React.GoalCheck();
        cfg.setEnabled(enabled);
        cfg.setEveryNThink(everyNThink);
        react.setGoalCheck(cfg);
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setReact(react);

        PromptCatalogHolder catalogHolder = mock(PromptCatalogHolder.class);
        when(catalogHolder.snapshot()).thenReturn(PromptCatalogSnapshot.of(0, List.of(
                new PromptCatalogEntry("react.goal-check", "react", "g",
                        true, 0, 1, GOAL_TEMPLATE, null))));
        return new GoalAlignmentMiddleware(props, catalogHolder);
    }

    private RuntimeContext ctx(AgentRole role) {
        return RuntimeContext.builder()
                .userId("u1")
                .sessionId("s1")
                .put(ProcessingStepMiddleware.CTX_BRIDGE_ID, bridgeId)
                .put(ProcessingStepMiddleware.CTX_AGENT_ROLE, role)
                .put(ProcessingStepMiddleware.CTX_USER_QUERY, "帮我对竞品做调研")
                .build();
    }

    private AgentState agentStateWithTasks() {
        AgentState state = mock(AgentState.class);
        when(state.getTasksContext()).thenReturn(new TaskContextState(List.of(
                Task.builder().id("t1").subject("分析竞品功能").description("对比竞品 A/B")
                        .state(Task.State.COMPLETED).build(),
                Task.builder().id("t2").subject("整理对比报告").description("汇总差异点")
                        .state(Task.State.IN_PROGRESS).build())));
        return state;
    }

    /** 注入后实际传给 next 的 ReasoningInput（null=本轮未注入/原样透传由断言消息数判定） */
    private ReasoningInput runReasoning(GoalAlignmentMiddleware mw, RuntimeContext rt,
            AgentState agentState, int inputSize) {
        ReasoningInput input = new ReasoningInput(initialMessages(inputSize), List.of(), null);
        AtomicReference<ReasoningInput> captured = new AtomicReference<>();
        Function<ReasoningInput, Flux<AgentEvent>> next = in -> {
            captured.set(in);
            return Flux.empty();
        };
        try (MockedStatic<RuntimeContext> staticRt = mockStatic(RuntimeContext.class)) {
            staticRt.when(() -> RuntimeContext.resolveAgentState(any(), any())).thenReturn(agentState);
            mw.onReasoning(mock(Agent.class), rt, input, next).collectList().block();
        }
        return captured.get();
    }

    private List<Msg> initialMessages(int n) {
        return List.of(Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("hi").build())
                .build());
    }

    private void markToolDone(RuntimeContext rt) {
        StepEventBridge.runState(bridgeId).markToolDone();
    }

    @Test
    void disabled_doesNotInject() {
        GoalAlignmentMiddleware mw = newMiddleware(false, 3);
        RuntimeContext rt = ctx(AgentRole.MAIN);
        ReasoningInput out = runReasoning(mw, rt, agentStateWithTasks(), 1);
        assertThat(out).isNotNull();
        assertThat(out.messages()).hasSize(1);
    }

    @Test
    void subRole_doesNotInject() {
        GoalAlignmentMiddleware mw = newMiddleware(true, 3);
        RuntimeContext rt = ctx(AgentRole.SUB);
        ReasoningInput out = runReasoning(mw, rt, agentStateWithTasks(), 1);
        assertThat(out.messages()).hasSize(1);
    }

    @Test
    void noTaskBoard_doesNotInject() {
        GoalAlignmentMiddleware mw = newMiddleware(true, 3);
        RuntimeContext rt = ctx(AgentRole.MAIN);
        ReasoningInput out = runReasoning(mw, rt, null, 1);
        assertThat(out.messages()).hasSize(1);
    }

    @Test
    void everyThreeIters_withToolGate_injectsEveryThirdIter() {
        GoalAlignmentMiddleware mw = newMiddleware(true, 3);
        RuntimeContext rt = ctx(AgentRole.MAIN);
        AgentState agentState = agentStateWithTasks();
        // iter 1、2：非间隔轮，不注入
        assertThat(runReasoning(mw, rt, agentState, 1).messages()).hasSize(1);
        assertThat(runReasoning(mw, rt, agentState, 1).messages()).hasSize(1);
        // iter 3：间隔轮，此前有工具完成（建板后的业务 tool）→ 注入
        markToolDone(rt);
        assertThat(runReasoning(mw, rt, agentState, 1).messages()).hasSize(2);
        // iter 4、5：非间隔轮，不注入
        assertThat(runReasoning(mw, rt, agentState, 1).messages()).hasSize(1);
        assertThat(runReasoning(mw, rt, agentState, 1).messages()).hasSize(1);
        // iter 6：间隔轮 + 期间又有工具完成 → 再注入（每 3 轮重复）
        markToolDone(rt);
        assertThat(runReasoning(mw, rt, agentState, 1).messages()).hasSize(2);
    }

    @Test
    void pureThink_afterInjection_doesNotRepeatBombard() {
        GoalAlignmentMiddleware mw = newMiddleware(true, 1);
        RuntimeContext rt = ctx(AgentRole.MAIN);
        AgentState agentState = agentStateWithTasks();
        // iter 1 注入（首轮无工具闸门）
        assertThat(runReasoning(mw, rt, agentState, 1).messages()).hasSize(2);
        // 连续纯 think：iter 2、3 无新工具完成 → 不重复注入
        assertThat(runReasoning(mw, rt, agentState, 1).messages()).hasSize(1);
        assertThat(runReasoning(mw, rt, agentState, 1).messages()).hasSize(1);
    }

    @Test
    void injectedMessage_containsSynthMetadataAndRenderedPlaceholders() {
        GoalAlignmentMiddleware mw = newMiddleware(true, 1);
        RuntimeContext rt = ctx(AgentRole.MAIN);
        AgentState agentState = agentStateWithTasks();
        ReasoningInput out = runReasoning(mw, rt, agentState, 1);
        Msg last = out.messages().get(out.messages().size() - 1);
        assertThat(last.getMetadata())
                .containsEntry("agentscope_synthetic", Boolean.TRUE)
                .containsEntry("agentscope_reminder_kind", "goal_check");
        String text = ((TextBlock) last.getContent().get(0)).getText();
        assertThat(text)
                .contains("原始任务：帮我对竞品做调研")
                .contains("当前进度：1/2 已完成 · 进行中：整理对比报告");
    }

    @Test
    void onActing_businessToolDone_marksToolGate() {
        GoalAlignmentMiddleware mw = newMiddleware(true, 3);
        RuntimeContext rt = ctx(AgentRole.MAIN);
        ToolUseBlock tu = mock(ToolUseBlock.class);
        when(tu.getId()).thenReturn("tu-1");
        when(tu.getName()).thenReturn("finance__x");
        ActingInput input = new ActingInput(List.of(tu));
        Function<ActingInput, Flux<AgentEvent>> next = in -> Flux.just(
                new ToolResultEndEvent("e-1", null, "r-1", "tu-1", "finance__x", ToolResultState.SUCCESS));
        mw.onActing(mock(Agent.class), rt, input, next).collectList().block();
        assertThat(StepEventBridge.runState(bridgeId).toolDoneSinceLastInject()).isTrue();
    }
}
