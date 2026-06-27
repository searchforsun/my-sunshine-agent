package com.sunshine.orchestrator.execution.agent;

import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStreamCollectorTest {

    @Test
    void ingest_content_streamsNodeResultAndIgnoresReasoning() {
        AgentStreamCollector collector = new AgentStreamCollector("n1", "合规分析", "compliance-check");
        assertThat(collector.ingest(StreamToken.reasoning("内部推理"))).isEmpty();

        var tokens = collector.ingest(StreamToken.content("最终"));
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).isStepDelta()).isTrue();
        assertThat(tokens.get(0).stepId()).isEqualTo("node-n1");
        assertThat(tokens.get(0).channel()).isEqualTo("result");
        assertThat(tokens.get(0).text()).isEqualTo("最终");
        assertThat(collector.content()).isEqualTo("最终");

        var more = collector.ingest(StreamToken.content("答复"));
        assertThat(more.get(0).text()).isEqualTo("答复");
        assertThat(collector.content()).isEqualTo("最终答复");
    }
}
