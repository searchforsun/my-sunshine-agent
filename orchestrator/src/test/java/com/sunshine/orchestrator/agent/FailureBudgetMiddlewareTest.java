package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import com.sunshine.orchestrator.taskboard.TodoTasksBridge;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 4.7.7 FailureBudgetMiddleware 单测（spec §9.1）：
 * 同参数死循环判定、成功清零、INTERRUPTED 不计数、元工具排除、一次性触发、下一轮注入。
 */
class FailureBudgetMiddlewareTest {

    private final String bridgeId = "fbm-bridge";

    @AfterEach
    void resetBridge() {
        StepEventBridge.resetRegistry();
    }

    private FailureBudgetMiddleware newMiddleware(int sameSignatureMax, int perToolMax) {
        AgentExecutionProperties.React react = new AgentExecutionProperties.React();
        AgentExecutionProperties.React.ToolFailureBudget cfg =
                new AgentExecutionProperties.React.ToolFailureBudget();
        cfg.setEnabled(true);
        cfg.setSameSignatureMax(sameSignatureMax);
        cfg.setPerToolMax(perToolMax);
        react.setToolFailureBudget(cfg);
        AgentExecutionProperties props = new AgentExecutionProperties();
        props.setReact(react);

        PromptCatalogHolder catalogHolder = mock(PromptCatalogHolder.class);
        when(catalogHolder.snapshot()).thenReturn(PromptCatalogSnapshot.of(0, List.of(
                new PromptCatalogEntry("react.tool-failure-budget", "react", "b",
                        true, 0, 1,
                        "<system-reminder>【执行受阻】工具 {toolName} 已连续失败 {failCount} 次（最近错误：{lastError}）。禁止重试。</system-reminder>",
                        null))));
        return new FailureBudgetMiddleware(props, catalogHolder);
    }

    private RuntimeContext ctx() {
        return RuntimeContext.builder()
                .userId("u1")
                .sessionId("s1")
                .put(ProcessingStepMiddleware.CTX_BRIDGE_ID, bridgeId)
                .build();
    }

    private ToolUseBlock toolUse(String id, String name) {
        ToolUseBlock tu = mock(ToolUseBlock.class);
        when(tu.getId()).thenReturn(id);
        when(tu.getName()).thenReturn(name);
        when(tu.getInput()).thenReturn(java.util.Map.of());
        return tu;
    }

    private void runActing(FailureBudgetMiddleware mw, List<ToolUseBlock> tools,
            List<AgentEvent> events) {
        ActingInput input = new ActingInput(tools);
        Function<ActingInput, Flux<AgentEvent>> next = in -> Flux.fromIterable(events);
        mw.onActing(mock(Agent.class), ctx(), input, next).collectList().block();
    }

    private ReasoningInput runReasoning(FailureBudgetMiddleware mw) {
        ReasoningInput input = new ReasoningInput(List.of(Msg.builder()
                .role(io.agentscope.core.message.MsgRole.USER)
                .content(io.agentscope.core.message.TextBlock.builder().text("你好").build())
                .build()), List.of(), null);
        java.util.concurrent.atomic.AtomicReference<ReasoningInput> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        Function<ReasoningInput, Flux<AgentEvent>> next = in -> {
            captured.set(in);
            return Flux.empty();
        };
        mw.onReasoning(mock(Agent.class), ctx(), input, next).collectList().block();
        return captured.get();
    }

    @Test
    void sameSignatureErrors_twice_injectsBudgetReminderNextReasoning() {
        FailureBudgetMiddleware mw = newMiddleware(2, 3);
        // 第 1 次 ERROR：signature 计数 1，未达阈值
        runActing(mw, List.of(toolUse("tu-1", "finance__x")), List.of(
                new ToolResultTextDeltaEvent("r-1", "tu-1", "finance__x", "err1"),
                new ToolResultEndEvent("e-1", null, "r-1", "tu-1", "finance__x", ToolResultState.ERROR)));
        AgentRunState state = StepEventBridge.runState(bridgeId);
        assertThat(state.failureCount("finance__x#" + sig("finance__x", "{}"))).isEqualTo(1);
        assertThat(state.hasPendingBudgetInjection()).isFalse();

        // 第 2 次同参数 ERROR：达阈值 → 待注入
        runActing(mw, List.of(toolUse("tu-2", "finance__x")), List.of(
                new ToolResultTextDeltaEvent("r-2", "tu-2", "finance__x", "err2"),
                new ToolResultEndEvent("e-2", null, "r-2", "tu-2", "finance__x", ToolResultState.ERROR)));
        assertThat(state.hasPendingBudgetInjection()).isTrue();
        assertThat(state.isBudgetExceeded("tu-2")).isTrue();
        // 该 toolUseId 标记换文案（PSM completeToolStep 用）
        assertThat(state.isBudgetExceeded("tu-2")).isTrue();
    }

    @Test
    void successBetweenFailures_clearsCount() {
        FailureBudgetMiddleware mw = newMiddleware(2, 3);
        // ERROR → SUCCESS → ERROR：第二次 ERROR 仍计数 1（成功清零）
        runActing(mw, List.of(toolUse("tu-1", "finance__x")), List.of(
                new ToolResultEndEvent("e-1", null, "r-1", "tu-1", "finance__x", ToolResultState.ERROR)));
        runActing(mw, List.of(toolUse("tu-2", "finance__x")), List.of(
                new ToolResultEndEvent("e-2", null, "r-2", "tu-2", "finance__x", ToolResultState.SUCCESS)));
        runActing(mw, List.of(toolUse("tu-3", "finance__x")), List.of(
                new ToolResultEndEvent("e-3", null, "r-3", "tu-3", "finance__x", ToolResultState.ERROR)));
        AgentRunState state = StepEventBridge.runState(bridgeId);
        assertThat(state.failureCount("finance__x")).isEqualTo(1);
        assertThat(state.hasPendingBudgetInjection()).isFalse();
    }

    @Test
    void interrupted_doesNotCountErrorBudget() {
        FailureBudgetMiddleware mw = newMiddleware(2, 2);
        runActing(mw, List.of(toolUse("tu-1", "finance__x")), List.of(
                new ToolResultEndEvent("e-1", null, "r-1", "tu-1", "finance__x", ToolResultState.INTERRUPTED)));
        runActing(mw, List.of(toolUse("tu-2", "finance__x")), List.of(
                new ToolResultEndEvent("e-2", null, "r-2", "tu-2", "finance__x", ToolResultState.INTERRUPTED)));
        AgentRunState state = StepEventBridge.runState(bridgeId);
        assertThat(state.failureCount("finance__x")).isZero();
        assertThat(state.hasPendingBudgetInjection()).isFalse();
    }

    @Test
    void denied_doesNotCount() {
        FailureBudgetMiddleware mw = newMiddleware(1, 1);
        runActing(mw, List.of(toolUse("tu-1", "finance__x")), List.of(
                new ToolResultEndEvent("e-1", null, "r-1", "tu-1", "finance__x", ToolResultState.DENIED)));
        assertThat(StepEventBridge.runState(bridgeId).hasPendingBudgetInjection()).isFalse();
    }

    @Test
    void metaTools_excludedFromBudget() {
        FailureBudgetMiddleware mw = newMiddleware(1, 1);
        runActing(mw, List.of(
                        toolUse("tu-1", TodoTasksBridge.TODO_WRITE),
                        toolUse("tu-2", SpawnSubagentTool.NAME),
                        toolUse("tu-3", RequestDecisionTool.NAME)),
                List.of(
                        new ToolResultEndEvent("e-1", null, "r-1", "tu-1", TodoTasksBridge.TODO_WRITE, ToolResultState.ERROR),
                        new ToolResultEndEvent("e-2", null, "r-2", "tu-2", SpawnSubagentTool.NAME, ToolResultState.ERROR),
                        new ToolResultEndEvent("e-3", null, "r-3", "tu-3", RequestDecisionTool.NAME, ToolResultState.ERROR)));
        AgentRunState state = StepEventBridge.runState(bridgeId);
        assertThat(state.failureCount(TodoTasksBridge.TODO_WRITE)).isZero();
        assertThat(state.hasPendingBudgetInjection()).isFalse();
    }

    @Test
    void pendingInjection_injectedIntoNextReasoning_withSynthMetadata() {
        FailureBudgetMiddleware mw = newMiddleware(1, 1);
        runActing(mw, List.of(toolUse("tu-1", "finance__x")), List.of(
                new ToolResultTextDeltaEvent("r-1", "tu-1", "finance__x", "连接超时"),
                new ToolResultEndEvent("e-1", null, "r-1", "tu-1", "finance__x", ToolResultState.ERROR)));

        ReasoningInput input = runReasoning(mw);
        assertThat(input).isNotNull();
        List<Msg> messages = input.messages();
        Msg last = messages.get(messages.size() - 1);
        assertThat(last.getMetadata())
                .containsEntry("agentscope_synthetic", Boolean.TRUE)
                .containsEntry("agentscope_reminder_kind", "tool_failure_budget");
        // 注入后再 reasoning：pending 已清空，不重复注入
        ReasoningInput second = runReasoning(mw);
        assertThat(second.messages()).hasSize(1);
    }

    private static String sig(String toolName, String input) {
        String normalized = input == null || input.isEmpty() ? "{}" : input;
        return org.springframework.util.DigestUtils.md5DigestAsHex(
                normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
