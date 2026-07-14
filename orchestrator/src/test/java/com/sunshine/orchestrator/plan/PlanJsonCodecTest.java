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
    void serializesLayoutWhenPresent() {
        PlanJson plan = new PlanJson("p1", "reason",
                List.of(new PlanNode("pg", "parallel-gateway", Map.of())),
                List.of(new PlanEdge("start", "pg")),
                Map.of("pg", new PlanLayoutPoint(100, 80)));
        String json = codec.toJson(plan);
        assertThat(json).contains("\"layout\"");
        assertThat(json).contains("\"x\":100");
    }

    @Test
    void serializesExclusiveEdgeCondition() {
        PlanJson plan = new PlanJson("p1", "xg",
                List.of(new PlanNode("xg", "exclusive-gateway", Map.of())),
                List.of(
                        new PlanEdge("start", "xg"),
                        new PlanEdge("xg", "a", new PlanEdgeCondition("{{q}}", "contains", "报销"), false),
                        new PlanEdge("xg", "b", null, true)));
        String json = codec.toJson(plan);
        assertThat(json).contains("\"op\":\"contains\"");
        assertThat(json).contains("\"default\":true");
        assertThat(json).contains("\"right\":\"报销\"");
    }

    @Test
    void traceRoundTrip() {
        List<PlanNodeTrace> traces = List.of(
                new PlanNodeTrace("n1", "llm", "completed", "ok", null, 100L, 200L));
        String json = codec.traceToJson(traces);
        assertThat(codec.traceFromJson(json)).hasSize(1);
    }
}
