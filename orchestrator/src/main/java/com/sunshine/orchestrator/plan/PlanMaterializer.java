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
        return toDefinition(ready, StringUtils.hasText(ready.planId()) ? ready.planId() : "dynamic-plan");
    }

    /** DB 静态 workflow — 保留 Studio 编辑的 answer prompt */
    public WorkflowDefinition materializeStored(PlanJson plan, String workflowId) {
        PlanJson enriched = displayNameEnricher.enrich(plan);
        return toDefinition(enriched, workflowId);
    }

    private WorkflowDefinition toDefinition(PlanJson ready, String id) {
        List<PlanExecutionSchedule.Step> steps = PlanExecutionSchedule.build(ready);
        List<String> linearOrder = steps.isEmpty()
                ? PlanLinearizer.linearOrder(ready).stream()
                        .filter(nodeId -> {
                            PlanNode node = ready.nodesById().get(nodeId);
                            return node != null && !"start".equals(node.type());
                        })
                        .toList()
                : PlanExecutionSchedule.flattenLinearOrder(steps);
        List<NodeSpec> specs = ready.nodes().stream()
                .filter(n -> !"start".equals(n.type()))
                .map(n -> new NodeSpec(
                        n.id(),
                        n.type(),
                        n.params(),
                        StringUtils.hasText(n.displayName()) ? n.displayName() : null))
                .toList();
        return WorkflowDefinition.from(id, specs, linearOrder, steps);
    }
}
