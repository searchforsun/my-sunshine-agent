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
  void serializesLayoutSizeWhenPresent() {
      PlanJson plan = new PlanJson("p1", "reason",
              List.of(new PlanNode("loop-1", "loop", Map.of(), "循环", null)),
              List.of(new PlanEdge("start", "loop-1")),
              Map.of("loop-1", new PlanLayoutPoint(100, 80, 560.0, 160.0)));
      String json = codec.toJson(plan);
      assertThat(json).contains("\"width\":560");
      assertThat(json).contains("\"height\":160");
      PlanJson back = new PlanJsonParser().parse(json);
      assertThat(back.layout().get("loop-1").width()).isEqualTo(560.0);
      assertThat(back.layout().get("loop-1").height()).isEqualTo(160.0);
  }

  @Test
  void serializesLoopParentId() {
        PlanJson plan = new PlanJson("p1", "loop",
                List.of(
                        new PlanNode("loop-1", "loop", Map.of("maxIterations", "3"), "循环", null),
                        new PlanNode("rag-1", "rag", Map.of("topK", "3"), "检索", "loop-1")),
                List.of(new PlanEdge("start", "loop-1"), new PlanEdge("loop-1", "answer")));
        String json = codec.toJson(plan);
        assertThat(json).contains("\"parentId\":\"loop-1\"");
    }

    @Test
    void traceRoundTrip() {
        List<PlanNodeTrace> traces = List.of(
                new PlanNodeTrace("n1", "llm", "completed", "ok", null, 100L, 200L));
        String json = codec.traceToJson(traces);
        assertThat(codec.traceFromJson(json)).hasSize(1);
    }
}
