package com.sunshine.orchestrator.routing;

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
        assertThat(plan.mode()).isEqualTo(ExecutionMode.REACT);
        assertThat(plan.workflowId()).isNull();
    }

    @Test
    void normalizesSimpleLlmAlias() {
        ExecutionPlan plan = parser.parse("{\"mode\":\"simple-llm\"}");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.SIMPLE_LLM);
    }

    @Test
    void legacySimpleMapsToSimpleLlm() {
        ExecutionPlan plan = parser.parse("simple");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.SIMPLE_LLM);
        assertThat(plan.intentLabel()).isEqualTo("simple-llm");
    }

    @Test
    void legacyFinanceMapsToFinanceListWorkflow() {
        ExecutionPlan plan = parser.parse("finance");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("finance-list");
        assertThat(plan.params()).containsEntry("status", "pending");
    }

    @Test
    void parseStoredIntentWorkflowLabel() {
        ExecutionPlan plan = parser.parseStoredIntent("workflow:knowledge-qa");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.WORKFLOW);
        assertThat(plan.workflowId()).isEqualTo("knowledge-qa");
    }

    @Test
    void parseStoredIntentSimpleLlm() {
        ExecutionPlan plan = parser.parseStoredIntent("simple-llm");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.SIMPLE_LLM);
    }

    @Test
    void parsesPlanWorkflowJson() {
        String json = """
                {"mode":"plan-workflow","workflowId":null,"params":{},"reason":"跨领域多步"}
                """;
        ExecutionPlan plan = parser.parse(json);
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
        assertThat(plan.intentLabel()).isEqualTo("plan-workflow");
        assertThat(plan.reason()).isEqualTo("跨领域多步");
    }

    @Test
    void parseStoredIntentPlanWorkflow() {
        ExecutionPlan plan = parser.parseStoredIntent("plan-workflow");
        assertThat(plan.mode()).isEqualTo(ExecutionMode.PLAN_WORKFLOW);
    }
}
