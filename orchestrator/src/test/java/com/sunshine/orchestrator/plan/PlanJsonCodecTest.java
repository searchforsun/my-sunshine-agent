package com.sunshine.orchestrator.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlanJsonCodecTest {

    private final PlanJsonCodec codec = new PlanJsonCodec(new ObjectMapper());

    @Test
    void roundTripPlanJson() {
        PlanJson plan = new PlanJson("p1", "reason",
                List.of(new PlanNode("n1", "rag", Map.of("topK", "3"))),
                List.of(new PlanEdge("start", "n1")));
        String json = codec.toJson(plan);
        assertThat(json).contains("\"planId\":\"p1\"");
        assertThat(json).contains("\"from\":\"start\"");
    }

    @Test
    void traceRoundTrip() {
        List<PlanNodeTrace> traces = List.of(
                new PlanNodeTrace("n1", "llm", "completed", "ok", null, 100L, 200L));
        String json = codec.traceToJson(traces);
        assertThat(codec.traceFromJson(json)).hasSize(1);
    }
}
