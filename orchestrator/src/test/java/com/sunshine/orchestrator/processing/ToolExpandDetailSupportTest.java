package com.sunshine.orchestrator.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExpandDetailSupportTest {

    @Test
    void returnsMultilineRawWhenSummaryIsShort() {
        String raw = """
                财务消息详情：
                - id=1001
                - 标题=Q2 差旅报销审批
                """;
        assertThat(ToolExpandDetailSupport.resolveExpandDetail("Q2 差旅报销审批", raw))
                .isEqualTo(raw.strip());
    }

    @Test
    void returnsNullWhenRawEqualsSummary() {
        assertThat(ToolExpandDetailSupport.resolveExpandDetail("查询完成", "查询完成")).isNull();
    }

    @Test
    void returnsNullWhenSingleLineSameLength() {
        assertThat(ToolExpandDetailSupport.resolveExpandDetail("短摘要", "略长")).isNull();
    }
}
