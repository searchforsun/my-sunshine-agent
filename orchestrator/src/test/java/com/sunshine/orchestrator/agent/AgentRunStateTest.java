package com.sunshine.orchestrator.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4.7.7 AgentRunState 单测：失败预算计数/触发/清零/一次性 + goal-check 轮次与工具闸门。
 */
class AgentRunStateTest {

    @Test
    void recordFailure_reachesThreshold_marksPendingOnce() {
        AgentRunState state = new AgentRunState();
        state.recordFailure("toolX#sig1", 2, "toolX", "err", "tu-1");
        assertThat(state.failureCount("toolX#sig1")).isEqualTo(1);
        assertThat(state.hasPendingBudgetInjection()).isFalse();

        state.recordFailure("toolX#sig1", 2, "toolX", "err", "tu-2");
        assertThat(state.failureCount("toolX#sig1")).isEqualTo(2);
        assertThat(state.hasPendingBudgetInjection()).isTrue();
        assertThat(state.drainPendingBudgetInjections()).hasSize(1);
        assertThat(state.hasPendingBudgetInjection()).isFalse();
    }

    @Test
    void recordFailure_aboveThreshold_neverTriggersAgain() {
        AgentRunState state = new AgentRunState();
        // 第 2 次触发
        state.recordFailure("toolX", 2, "toolX", "e1", "tu-1");
        state.recordFailure("toolX", 2, "toolX", "e1", "tu-2");
        assertThat(state.drainPendingBudgetInjections()).hasSize(1);
        // 第 3、4 次：key 已触发过 → 不再登记
        state.recordFailure("toolX", 2, "toolX", "e1", "tu-3");
        state.recordFailure("toolX", 2, "toolX", "e1", "tu-4");
        assertThat(state.hasPendingBudgetInjection()).isFalse();
        assertThat(state.drainPendingBudgetInjections()).isEmpty();
    }

    @Test
    void resetFailure_successClearsBothDimensions() {
        AgentRunState state = new AgentRunState();
        state.recordFailure("toolX", 3, "toolX", "e1", "tu-1");
        state.recordFailure("toolX#sig1", 3, "toolX", "e1", "tu-1");
        assertThat(state.failureCount("toolX")).isEqualTo(1);
        assertThat(state.failureCount("toolX#sig1")).isEqualTo(1);

        state.resetFailure("toolX");
        state.resetFailure("toolX#sig1");
        assertThat(state.failureCount("toolX")).isZero();
        assertThat(state.failureCount("toolX#sig1")).isZero();
    }

    @Test
    void sameToolUseId_doubleDimension_marksBudgetExceededOnce() {
        AgentRunState state = new AgentRunState();
        // 同一 toolUseId：signature 维度先触发，toolName 维度（同轮同 toolUseId）不再登记
        state.recordFailure("toolX#sig1", 1, "toolX", "e1", "tu-1");
        state.recordFailure("toolX", 1, "toolX", "e1", "tu-1");
        assertThat(state.drainPendingBudgetInjections()).hasSize(1);
        assertThat(state.isBudgetExceeded("tu-1")).isTrue();
        assertThat(state.isBudgetExceeded("tu-other")).isFalse();
    }

    @Test
    void goalCheck_iterAndToolGate() {
        AgentRunState state = new AgentRunState();
        assertThat(state.goalCheckLastInjectedIter()).isZero();
        assertThat(state.toolDoneSinceLastInject()).isFalse();

        state.markToolDone();
        assertThat(state.toolDoneSinceLastInject()).isTrue();

        int iter = state.nextReasoningIter();
        assertThat(iter).isEqualTo(1);
        state.markGoalCheckInjected(iter);
        assertThat(state.goalCheckLastInjectedIter()).isEqualTo(1);
        assertThat(state.toolDoneSinceLastInject()).isFalse();
    }
}
