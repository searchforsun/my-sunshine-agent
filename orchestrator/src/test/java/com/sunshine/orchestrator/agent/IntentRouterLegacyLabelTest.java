package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRouterLegacyLabelTest {

    @Test
    void simpleLlmMapsToSimple() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.SIMPLE_LLM, null, Map.of(), "test");
        assertThat(IntentRouter.toLegacyIntentLabel(plan)).isEqualTo("simple");
    }

    @Test
    void knowledgeWorkflowMapsToKnowledge() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "test");
        assertThat(IntentRouter.toLegacyIntentLabel(plan)).isEqualTo("knowledge");
    }

    @Test
    void financeListMapsToFinance() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-list", Map.of(), "test");
        assertThat(IntentRouter.toLegacyIntentLabel(plan)).isEqualTo("finance");
    }

    @Test
    void reactMapsToSimpleForLegacyController() {
        ExecutionPlan plan = ExecutionPlan.reactFallback("test");
        assertThat(IntentRouter.toLegacyIntentLabel(plan)).isEqualTo("simple");
    }

    @Test
    void planWorkflowMapsToReactForLegacyController() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "test");
        assertThat(IntentRouter.toLegacyIntentLabel(plan)).isEqualTo("react");
    }
}
