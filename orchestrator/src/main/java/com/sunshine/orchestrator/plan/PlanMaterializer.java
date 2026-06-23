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

    public WorkflowDefinition materialize(PlanJson plan) {
        PlanJson enriched = displayNameEnricher.enrich(plan);
        List<String> linearOrder = PlanLinearizer.linearOrder(enriched).stream()
                .filter(id -> {
                    PlanNode node = enriched.nodesById().get(id);
                    return node != null && !"start".equals(node.type());
                })
                .toList();
        List<NodeSpec> specs = enriched.nodes().stream()
                .filter(n -> !"start".equals(n.type()))
                .map(n -> new NodeSpec(
                        n.id(),
                        n.type(),
                        n.params(),
                        StringUtils.hasText(n.displayName()) ? n.displayName() : null))
                .toList();
        String id = StringUtils.hasText(enriched.planId()) ? enriched.planId() : "dynamic-plan";
        return WorkflowDefinition.from(id, specs, linearOrder);
    }
}
