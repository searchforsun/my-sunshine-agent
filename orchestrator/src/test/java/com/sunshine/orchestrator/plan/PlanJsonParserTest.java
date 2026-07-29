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
                    {"id":"n2","type":"tool","params":{"tool":"sdk__sunshine-finance__list_my_expenses","status":"pending"}},
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

    @Test
    void parsesExclusiveEdgeConditionAndDefault() {
        String json = """
                {
                  "planId": "xg-1",
                  "reason": "条件分支",
                  "nodes": [
                    {"id":"xg-1","type":"exclusive-gateway","params":{}},
                    {"id":"rag-a","type":"rag","params":{"topK":"3"}},
                    {"id":"rag-b","type":"rag","params":{"topK":"3"}},
                    {"id":"answer","type":"answer","params":{}}
                  ],
                  "edges": [
                    {"from":"start","to":"xg-1"},
                    {"from":"xg-1","to":"rag-a","condition":{"logic":"and","items":[{"left":"{{start.userQuery}}","op":"contains","right":"报销"}]}},
                    {"from":"xg-1","to":"rag-b","default":true},
                    {"from":"rag-a","to":"answer"},
                    {"from":"rag-b","to":"answer"}
                  ]
                }
                """;
        PlanJson plan = parser.parse(json);
        assertThat(PlanExecutionSchedule.validateExclusiveTopology(plan)).isNull();
        PlanEdge cond = plan.edges().stream()
                .filter(e -> "rag-a".equals(e.to()))
                .findFirst()
                .orElseThrow();
        assertThat(cond.hasCondition()).isTrue();
        assertThat(cond.condition().items().get(0).op()).isEqualTo("contains");
        assertThat(cond.condition().items().get(0).right()).isEqualTo("报销");
        PlanEdge def = plan.edges().stream()
                .filter(e -> "rag-b".equals(e.to()))
                .findFirst()
                .orElseThrow();
        assertThat(def.isDefault()).isTrue();
    }

    @Test
    void parsesLoopParentId() {
        String json = """
                {
                  "planId": "lp-1",
                  "reason": "循环",
                  "nodes": [
                    {"id":"loop-1","type":"loop","params":{
                      "conditions":[{"left":"{{start.userQuery}}","op":"contains","right":"继续"}],
                      "conditionLogic":"and",
                      "maxIterations":"3","onMaxIterations":"exit",
                      "retry.maxAttempts":"1","retry.backoffMs":"500","retry.onFailure":"fail_fast"
                    }},
                    {"id":"rag-b","type":"rag","parentId":"loop-1","params":{"query":"{{start.userQuery}}","topK":"3"}},
                    {"id":"answer","type":"answer","params":{}}
                  ],
                  "edges": [
                    {"from":"start","to":"loop-1"},
                    {"from":"loop-1","to":"answer"}
                  ]
                }
                """;
        PlanJson plan = parser.parse(json);
        assertThat(plan.nodesById().get("rag-b").parentId()).isEqualTo("loop-1");
        assertThat(PlanExecutionSchedule.validateLoopTopology(plan)).isNull();
        assertThat(PlanExecutionSchedule.build(plan).get(0)).isInstanceOf(PlanExecutionSchedule.Loop.class);
    }

    @Test
    void parseInputsFromNodeJson() {
        String json = """
                {"planId":"p1","reason":"test","nodes":[
                  {"id":"start","type":"start","params":{}},
                  {"id":"tool_1","type":"tool","params":{"tool":"sdk__app__tool"},
                   "inputs":[
                     {"name":"status","source":"{{plan.params.status}}","type":"string","required":true},
                     {"name":"userId","source":"{{start.userId}}","type":"string","required":false}
                   ]},
                  {"id":"answer","type":"answer","params":{"prompt":"{{tool_1.output}}"}}
                ],"edges":[{"from":"start","to":"tool_1"},{"from":"tool_1","to":"answer"}]}
                """;
        PlanJson plan = parser.parse(json);
        PlanNode toolNode = plan.nodesById().get("tool_1");
        assertThat(toolNode.inputs()).hasSize(2);
        assertThat(toolNode.inputs().get(0).name()).isEqualTo("status");
        assertThat(toolNode.inputs().get(0).required()).isTrue();
        assertThat(toolNode.inputs().get(1).name()).isEqualTo("userId");
        assertThat(toolNode.inputs().get(1).required()).isFalse();
    }

    @Test
    void parsesCompositeEdgeCondition() {
        String json = """
                {
                  "planId": "xg-2",
                  "reason": "复合条件分支",
                  "nodes": [
                    {"id":"xg-1","type":"exclusive-gateway","params":{}},
                    {"id":"rag-a","type":"rag","params":{"topK":"3"}},
                    {"id":"rag-b","type":"rag","params":{"topK":"3"}},
                    {"id":"answer","type":"answer","params":{}}
                  ],
                  "edges": [
                    {"from":"start","to":"xg-1"},
                    {"from":"xg-1","to":"rag-a","condition":{
                      "logic":"or",
                      "items":[
                        {"left":"{{start.userQuery}}","op":"contains","right":"报销"},
                        {"left":"{{start.userQuery}}","op":"contains","right":"发票"}
                      ]
                    }},
                    {"from":"xg-1","to":"rag-b","default":true},
                    {"from":"rag-a","to":"answer"},
                    {"from":"rag-b","to":"answer"}
                  ]
                }
                """;
        PlanJson plan = parser.parse(json);
        PlanEdge cond = plan.edges().stream()
                .filter(e -> "rag-a".equals(e.to()))
                .findFirst()
                .orElseThrow();
        assertThat(cond.hasCondition()).isTrue();
        assertThat(cond.condition().logic()).isEqualTo("or");
        assertThat(cond.condition().items()).hasSize(2);
        assertThat(cond.condition().items().get(0).op()).isEqualTo("contains");
        assertThat(cond.condition().items().get(1).right()).isEqualTo("发票");
    }

    @Test
    void parsesLoopConditionsArray() {
        String json = """
                {
                  "planId": "lp-2",
                  "reason": "多条件循环",
                  "nodes": [
                    {"id":"loop-1","type":"loop","params":{
                      "conditions":[
                        {"left":"{{rag-b.hitCount}}","op":"gt","right":"0"},
                        {"left":"{{rag-b.output}}","op":"not_contains","right":"已完成"}
                      ],
                      "conditionLogic":"and",
                      "maxIterations":"3","onMaxIterations":"exit"
                    }},
                    {"id":"rag-b","type":"rag","parentId":"loop-1","params":{"query":"{{start.userQuery}}","topK":"3"}},
                    {"id":"answer","type":"answer","params":{}}
                  ],
                  "edges": [
                    {"from":"start","to":"loop-1"},
                    {"from":"loop-1","to":"answer"}
                  ]
                }
                """;
        PlanJson plan = parser.parse(json);
        assertThat(PlanExecutionSchedule.validateLoopTopology(plan)).isNull();
        assertThat(PlanExecutionSchedule.build(plan).get(0)).isInstanceOf(PlanExecutionSchedule.Loop.class);
    }
}
