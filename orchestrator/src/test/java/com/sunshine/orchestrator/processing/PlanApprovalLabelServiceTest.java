package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanApprovalLabelServiceTest {

    private PlanApprovalLabelService labelService;

    @BeforeEach
    void setUp() {
        labelService = new PlanApprovalLabelService(TimelinePromptCatalog.withDefaults());
        PlanApprovalLabels.bind(labelService);
    }

    @AfterEach
    void tearDown() {
        PlanApprovalLabels.bind(null);
    }

    @Test
    void readsDefaultTimelineConfig() {
        assertThat(PlanApprovalLabels.awaiting()).isEqualTo("等待确认执行计划");
        assertThat(PlanApprovalLabels.approved()).isEqualTo("已确认执行计划");
        assertThat(PlanApprovalLabels.regenerating()).isEqualTo("正在根据修改意见重新规划…");
        assertThat(PlanApprovalLabels.timedOut()).isEqualTo("确认超时，将改由自主智能体继续");
    }
}
