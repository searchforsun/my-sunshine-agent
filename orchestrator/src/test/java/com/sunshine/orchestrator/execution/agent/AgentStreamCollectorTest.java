package com.sunshine.orchestrator.execution.agent;

import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamCollectorTest {

    @Test
    void ingest_segmentedContent_passesThroughWithNodeScope() {
        AgentStreamCollector collector = new AgentStreamCollector("n1", "合规分析", "compliance-check");
        assertThat(collector.ingest(StreamToken.reasoning("内部推理"))).isEmpty();

        var start = collector.ingest(StreamToken.contentStart("content-1", "think"));
        assertThat(start).hasSize(1);
        assertThat(start.get(0).isContentStart()).isTrue();
        assertThat(start.get(0).scopeNodeStepId()).isEqualTo("node-n1");

        var chunk = collector.ingest(StreamToken.contentInSegment("content-1", "最终"));
        assertThat(chunk).hasSize(1);
        assertThat(chunk.get(0).isContent()).isTrue();
        assertThat(chunk.get(0).scopeNodeStepId()).isEqualTo("node-n1");
        assertThat(collector.content()).isEqualTo("最终");

        var end = collector.ingest(StreamToken.contentEnd("content-1"));
        assertThat(end.get(0).isContentEnd()).isTrue();
        assertThat(end.get(0).scopeNodeStepId()).isEqualTo("node-n1");
    }

    @Test
    void ingest_legacyContent_streamsNodeResult() {
        AgentStreamCollector collector = new AgentStreamCollector("n1", "合规分析", "compliance-check");
        var tokens = collector.ingest(StreamToken.content("最终"));
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).isStepDelta()).isTrue();
        assertThat(tokens.get(0).stepId()).isEqualTo("node-n1");
        assertThat(tokens.get(0).channel()).isEqualTo("result");
        assertThat(collector.content()).isEqualTo("最终");
    }
}
