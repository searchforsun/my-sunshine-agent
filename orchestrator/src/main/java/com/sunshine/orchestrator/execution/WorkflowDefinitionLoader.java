package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.WorkflowManagerClient;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanJsonParser;
import com.sunshine.orchestrator.plan.PlanMaterializer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/** 从 workflow-manager DB 加载 DAG 定义 */
@Component
@RequiredArgsConstructor
public class WorkflowDefinitionLoader {

    private final WorkflowManagerClient workflowManagerClient;
    private final PlanJsonParser planJsonParser;
    private final PlanMaterializer planMaterializer;

    public record WorkflowLoadBundle(WorkflowDefinition definition, PlanJson sourcePlan) {
    }

    public Optional<WorkflowLoadBundle> loadBundle(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            return Optional.empty();
        }
        return workflowManagerClient.fetchPublished(workflowId.strip())
                .map(published -> {
                    String raw = workflowManagerClient.planToJson(published.plan());
                    PlanJson plan = planJsonParser.parse(raw);
                    WorkflowDefinition def = planMaterializer.materializeStored(plan, workflowId.strip());
                    return new WorkflowLoadBundle(def, plan);
                });
    }

    public Optional<WorkflowDefinition> load(String workflowId) {
        return loadBundle(workflowId).map(WorkflowLoadBundle::definition);
    }
}
