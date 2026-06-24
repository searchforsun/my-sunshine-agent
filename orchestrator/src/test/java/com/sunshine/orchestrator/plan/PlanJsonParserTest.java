package com.sunshine.orchestrator.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanJsonParserTest {

    private final PlanJsonParser parser = new PlanJsonParser();

    @Test
    void parsesValidPlanJson() {
        String json = """
                {
                  "planId": "p-1",
                  "reason": "制度+财务+合规",
                  "nodes": [
                    {"id":"n1","type":"rag","params":{"topK":"3"}},
                    {"id":"n2","type":"tool","params":{"tool":"list_finance_messages","status":"pending"}},
                    {"id":"n3","type":"agent","params":{"skill":"compliance-check","query":"对比合规性","context":"{{n2.output}}"}}
                  ],
                  "edges": [
                    {"from":"start","to":"n1"},
                    {"from":"n1","to":"n2"},
                    {"from":"n2","to":"n3"}
                  ]
                }
                """;
        PlanJson plan = PlanNormalizer.normalize(parser.parse(json));
        assertThat(plan.planId()).isEqualTo("p-1");
        assertThat(plan.nodes()).hasSize(4);
        assertThat(plan.edges()).hasSize(4);
        assertThat(PlanLinearizer.linearOrder(plan))
                .containsExactly("n1", "n2", "n3", PlanNormalizer.ANSWER_NODE_ID);
    }

    @Test
    void rejectsTruncatedPlannerJson() {
        String truncated = """
                {"planId":null,"reason":"分步进行报销合规分析","nodes":[{"id":"n1","type":"rag","displayName":"检索差旅报销政策","params":{"query":"差旅报销政策","topK":3}}""";
        assertThatThrownBy(() -> parser.parse(truncated))
                .isInstanceOf(PlanParseException.class);
    }

    @Test
    void rejectsEmptyOutput() {
        assertThatThrownBy(() -> parser.parse("  "))
                .isInstanceOf(PlanParseException.class);
    }
}
