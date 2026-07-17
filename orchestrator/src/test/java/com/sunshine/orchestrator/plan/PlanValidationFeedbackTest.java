package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanValidationFeedbackTest {

    @Test
    void loopCrossFrameGetsActionableReplanFeedback() {
        String fb = PlanValidationFeedback.formatForReplan("禁止跨框边 lp1→n1");
        assertThat(fb).contains("LOOP_CROSS_FRAME");
        assertThat(fb).contains("lp1→n1");
        assertThat(fb).contains("parentId");
        assertThat(fb).contains("删除 edge");
    }

    @Test
    void parallelJoinGetsHint() {
        String fb = PlanValidationFeedback.formatForReplan("join 节点 j1 入度须 ≥ 2");
        assertThat(fb).contains("PARALLEL_JOIN_IN");
        assertThat(fb).contains("parallel-gateway");
    }

    @Test
    void unknownToolGetsCatalogHint() {
        String fb = PlanValidationFeedback.formatForReplan("未知工具: bad_tool");
        assertThat(fb).contains("UNKNOWN_TOOL");
        assertThat(fb).contains("Tool 目录");
    }
}
