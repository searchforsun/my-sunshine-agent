package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.DecisionStepMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.processing.ThinkStepIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingStepLifecycleOpsDecisionTest {

    @Test
    void findReactAwaitingDecisionStep_returnsLatestAwaiting() {
        ProcessingStep older = decisionStep("decision-old", "awaiting", null);
        ProcessingStep newer = decisionStep("decision-new", "paused", null);
        ProcessingStep resolved = decisionStep("decision-done", "done", "plan_a");
        List<ProcessingStep> steps = List.of(
                doneStep("intent"),
                doneStep("think"),
                older,
                resolved,
                newer);

        ProcessingStep found = ProcessingStepLifecycleOps.findReactAwaitingDecisionStep(steps);

        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo("decision-new");
    }

    @Test
    void findReactAwaitingDecisionStep_skipsNodePrefixedAndResolvedChoice() {
        List<ProcessingStep> steps = List.of(
                decisionStep("node-decision-x", "awaiting", null),
                decisionStep("decision-resolved", "paused", "plan_a"));

        assertThat(ProcessingStepLifecycleOps.findReactAwaitingDecisionStep(steps)).isNull();
    }

    @Test
    void truncateToLastCompleteThink_preservesAwaitingDecisionStep() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(
                doneStep("intent"),
                doneStep("think"),
                decisionStep("decision-tok-1", "awaiting", null)));

        ThinkStepIds.truncateToLastCompleteThink(steps);

        assertThat(steps).extracting(ProcessingStep::id)
                .containsExactly("intent", "think", "decision-tok-1");
        assertThat(ProcessingStepLifecycleOps.findReactAwaitingDecisionStep(steps)).isNotNull();
    }

    private static ProcessingStep doneStep(String id) {
        return new ProcessingStep(
                id, id, "done",
                new StepSummary(null, null, "ok"),
                1L, 2L, 1L, null, null, null, null,
                2L, id, null, null, null, null);
    }

    private static ProcessingStep decisionStep(String id, String lifecycle, String choice) {
        DecisionStepMeta decision = new DecisionStepMeta(
                "tok",
                "选哪个方案？",
                List.of(
                        new DecisionOption("plan_a", "方案A", "稳妥", false),
                        new DecisionOption("plan_b", "方案B", null, false)),
                false,
                System.currentTimeMillis() + 60_000,
                choice,
                null);
        return new ProcessingStep(
                id,
                "decision",
                lifecycle,
                new StepSummary(null, "等待决策", null),
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                2L,
                "选哪个方案？",
                StepMetadata.withDecision(null, decision),
                null,
                null,
                null);
    }
}
