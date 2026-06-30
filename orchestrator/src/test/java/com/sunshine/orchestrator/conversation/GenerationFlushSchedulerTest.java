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
                "识别意图",
                null,
                null,
                null
        );

        JsonNode node = OM.readTree(scheduler.metaStep(running));
        JsonNode summary = node.get("summary");

        assertThat(summary.has("active")).isTrue();
        assertThat(summary.get("active").asText()).isEqualTo("正在分析用户输入");
        assertThat(summary.has("before")).isFalse();
        assertThat(summary.has("after")).isFalse();
        assertThat(node.get("label").asText()).isEqualTo("识别意图");
        assertThat(node.has("status")).isFalse();
        assertThat(node.get("lifecycle").asText()).isEqualTo("running");
    }

    @Test
    @DisplayName("metaContent 带 afterStepId 供 ReAct 正文穿插")
    void metaContent_emitsAfterStepIdWhenPresent() throws Exception {
        GenerationFlushScheduler scheduler = new GenerationFlushScheduler(mock(), mock());

        JsonNode withAnchor = OM.readTree(scheduler.metaContent("正文", "think-2"));
        assertThat(withAnchor.get("type").asText()).isEqualTo("content");
        assertThat(withAnchor.get("text").asText()).isEqualTo("正文");
        assertThat(withAnchor.get("afterStepId").asText()).isEqualTo("think-2");

        JsonNode plain = OM.readTree(scheduler.metaContent("正文", null));
        assertThat(plain.has("afterStepId")).isFalse();
    }

    @Test
    @DisplayName("ReAct 正文分段 SSE")
    void metaContentSegmentLifecycle() throws Exception {
        GenerationFlushScheduler scheduler = new GenerationFlushScheduler(mock(), mock());

        JsonNode start = OM.readTree(scheduler.metaContentStart("content-1", "think"));
        assertThat(start.get("type").asText()).isEqualTo("content_start");
        assertThat(start.get("segmentId").asText()).isEqualTo("content-1");
        assertThat(start.get("afterStepId").asText()).isEqualTo("think");

        JsonNode chunk = OM.readTree(scheduler.metaContentInSegment("content-1", "你好"));
        assertThat(chunk.get("type").asText()).isEqualTo("content");
        assertThat(chunk.get("segmentId").asText()).isEqualTo("content-1");
        assertThat(chunk.has("afterStepId")).isFalse();

        JsonNode end = OM.readTree(scheduler.metaContentEnd("content-1"));
        assertThat(end.get("type").asText()).isEqualTo("content_end");
        assertThat(end.get("segmentId").asText()).isEqualTo("content-1");
    }

    @Test
    @DisplayName("子 Agent 正文分段带 nodeStepId")
    void metaContentSegmentWithNodeStepId() throws Exception {
        GenerationFlushScheduler scheduler = new GenerationFlushScheduler(mock(), mock());

        JsonNode start = OM.readTree(scheduler.metaContentStart("content-1", "think", "node-a1"));
        assertThat(start.get("nodeStepId").asText()).isEqualTo("node-a1");

        JsonNode chunk = OM.readTree(scheduler.metaContentInSegment("content-1", "你好", "node-a1"));
        assertThat(chunk.get("nodeStepId").asText()).isEqualTo("node-a1");

        JsonNode end = OM.readTree(scheduler.metaContentEnd("content-1", "node-a1"));
        assertThat(end.get("nodeStepId").asText()).isEqualTo("node-a1");
    }
}
