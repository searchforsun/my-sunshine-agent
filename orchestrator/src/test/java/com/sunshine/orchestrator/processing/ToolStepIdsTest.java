package com.sunshine.orchestrator.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolStepIdsTest {

    @Test
    void forInvocation_appendsEpochMs() {
        assertThat(ToolStepIds.forInvocation("tool-sdk__sunshine-finance__summarize_my_expenses", 1_718_750_000_123L))
                .isEqualTo("tool-sdk__sunshine-finance__summarize_my_expenses@1718750000123");
    }

    @Test
    void catalogToolName_parsesTimestampSuffix() {
        assertThat(ToolStepIds.catalogToolName("rag@1718750000123")).isEqualTo("search_knowledge");
        assertThat(ToolStepIds.catalogToolName("tool-sdk__sunshine-finance__list_my_expenses@99"))
                .isEqualTo("sdk__sunshine-finance__list_my_expenses");
    }

    @Test
    void invokeTimeLabel_formatsClock() {
        assertThat(ToolStepIds.invokeTimeLabel("tool-x@1718750000123")).matches("\\d{2}:\\d{2}:\\d{2}");
    }
}
