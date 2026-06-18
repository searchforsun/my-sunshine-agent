package com.sunshine.orchestrator.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.processing.StepSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationFlushSchedulerTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    @DisplayName("metaStep SSE 仅下发当前阶段 summary")
    void metaStep_emitsCurrentPhaseSummaryOnly() throws Exception {
        GenerationFlushScheduler scheduler = new GenerationFlushScheduler(mock(), mock());

        ProcessingStep running = new ProcessingStep(
                "intent",
                "intent",
                "running",
                new StepSummary("阅读问题", "正在分析用户输入", null),
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                1L,
                "running",
                "识别意图",
                null
        );

        JsonNode node = OM.readTree(scheduler.metaStep(running));
        JsonNode summary = node.get("summary");

        assertThat(summary.has("active")).isTrue();
        assertThat(summary.get("active").asText()).isEqualTo("正在分析用户输入");
        assertThat(summary.has("before")).isFalse();
        assertThat(summary.has("after")).isFalse();
    }
}
