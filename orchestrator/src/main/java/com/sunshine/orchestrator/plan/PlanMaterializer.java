package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/** PlanJson → WorkflowDefinition（线性 MVP） */
@Component
@RequiredArgsConstructor
public class PlanMaterializer {

    private final PlanDisplayNameEnricher displayNameEnricher;
    private final PlanAnswerPromptAssembler answerPromptAssembler;

    public WorkflowDefinition materialize(PlanJson plan) {
        PlanJson enriched = displayNameEnricher.enrich(plan);
        PlanJson ready = answerPromptAssembler.apply(enriched);
        List<String> linearOrder = PlanLinearizer.linearOrder(ready).stream()
                .filter(id -> {
                    PlanNode node = ready.nodesById().get(id);
                    return node != null && !"start".equals(node.type());
                })
                .toList();
        List<NodeSpec> specs = ready.nodes().stream()
                .filter(n -> !"start".equals(n.type()))
                .map(n -> new NodeSpec(
                        n.id(),
                        n.type(),
                        n.params(),
                        StringUtils.hasText(n.displayName()) ? n.displayName() : null))
                .toList();
        String id = StringUtils.hasText(ready.planId()) ? ready.planId() : "dynamic-plan";
        return WorkflowDefinition.from(id, specs, linearOrder);
    }
}
