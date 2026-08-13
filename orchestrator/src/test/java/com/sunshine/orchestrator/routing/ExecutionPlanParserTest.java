package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPlanParserTest {

    private final ExecutionPlanParser parser = new ExecutionPlanParser();

    @Test
    void parsesWorkflowJson() {
        String json = """
                {"mode":"workflow","workflowId":"knowledge-qa","params":{},"reason":"查制度"}
                """;
        ExecutionPlan plan = parser.parse(json);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
        assertThat(plan.reason()).isEqualTo("查制度");
    }

    @Test
    void invalidJsonFallsBackToReact() {
        ExecutionPlan plan = parser.parse("not json");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.workflowId()).isNull();
    }

    @Test
    void unknownMode_simpleLlmFallsToReact() {
        ExecutionPlan plan = parser.parse("{\"mode\":\"simple-llm\"}");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
    }

    @Test
    void unknownStoredIntentFallsBackToReact() {
        ExecutionPlan plan = parser.parse("simple");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
    }

    @Test
    void parseStoredIntentWorkflowLabel() {
        ExecutionPlan plan = parser.parseStoredIntent("workflow:knowledge-qa");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
    }

    @Test
    void parseStoredIntent_unknownSimpleLlmFallsToReact() {
        ExecutionPlan plan = parser.parseStoredIntent("simple-llm");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
    }

    @Test
    void parsesSkillIdTopLevel() {
        String json = """
                {"mode":"pro","workflowId":null,"skillId":"sandbox-coding-demo","params":{},"reason":"沙箱脚本分析"}
                """;
        ExecutionPlan plan = parser.parse(json);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PRO);
        assertThat(plan.params().get(SkillBindingOutcome.PARAM_SKILL)).isEqualTo("sandbox-coding-demo");
        assertThat(plan.reason()).isEqualTo("沙箱脚本分析");
    }

    @Test
    void parsesTrackAFields_ignoresPlanModeAndExecutionMode() {
        String json = """
                {"planMode":"harness","executionMode":"workflow","agentIds":["a1","a2"],\
                "skillIds":["s1","s2"],"reason":"轨A"}
                """;
        ExecutionPlan plan = parser.parse(json);
        // mode 缺省 → FAST；planMode/executionMode 不参与 mode
        assertThat(plan.mode()).isEqualTo(ExecutionMode.FAST);
        assertThat(plan.params()).containsEntry("agentIds", "a1,a2");
        assertThat(plan.params()).containsEntry("skillIds", "s1,s2");
        assertThat(plan.params()).containsEntry(SkillBindingOutcome.PARAM_SKILL, "s1");
    }
}
