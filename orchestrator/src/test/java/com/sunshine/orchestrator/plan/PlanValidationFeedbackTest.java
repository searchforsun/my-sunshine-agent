package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanValidationFeedbackTest {

    @Test
    void loopCrossFrameGetsActionableReplanFeedback() {
        String fb = PlanValidationFeedback.formatForReplan(PlanValidationIssue.of(
                PlanValidationCode.LOOP_CROSS_FRAME,
                "edge lp1→n1 跨 loop 框内外（loop 容器与 parentId body 之间禁止连边）",
                """
                1. 删除 edge lp1→n1
                2. 若 lp1 为 loop、n1 为 body：body 保留 "parentId":"lp1"，外图 edges 只保留 start→lp1
                3. 单 body 时勿写 loop→body 或框内 edges；多 body 时框内才写 b1→b2（同 parentId）
                4. 外图勿连 answer（引擎自动拼接）"""));
        assertThat(fb).contains("LOOP_CROSS_FRAME");
        assertThat(fb).contains("lp1→n1");
        assertThat(fb).contains("parentId");
        assertThat(fb).contains("删除 edge");
    }

    @Test
    void parallelJoinGetsHint() {
        String fb = PlanValidationFeedback.formatForReplan(PlanValidationIssue.of(
                PlanValidationCode.PARALLEL_JOIN_IN,
                "join 节点 j1 入度须 ≥ 2"));
        assertThat(fb).contains("PARALLEL_JOIN_IN");
        assertThat(fb).contains("parallel-gateway");
    }

    @Test
    void unknownToolGetsCatalogHint() {
        String fb = PlanValidationFeedback.formatForReplan(PlanValidationIssue.of(
                PlanValidationCode.UNKNOWN_TOOL,
                "未知工具: bad_tool"));
        assertThat(fb).contains("UNKNOWN_TOOL");
        assertThat(fb).contains("Tool 目录");
    }

    @Test
    void nullIssueFallsBackToUnknown() {
        String fb = PlanValidationFeedback.formatForReplan(null);
        assertThat(fb).contains("UNKNOWN");
        assertThat(fb).contains("未知校验错误");
    }
}
