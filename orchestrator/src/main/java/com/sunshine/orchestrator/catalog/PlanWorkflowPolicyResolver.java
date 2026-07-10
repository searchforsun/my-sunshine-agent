package com.sunshine.orchestrator.catalog;

import com.sunshine.common.tool.PlanWorkflowExecutionPolicy;
import com.sunshine.orchestrator.client.ExecutionModePolicyClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlanWorkflowPolicyResolver {

    private final ExecutionModePolicyClient executionModePolicyClient;

    public PlanWorkflowExecutionPolicy resolve(String tenantId) {
        return executionModePolicyClient.fetchPlanWorkflow(normalizeTenant(tenantId));
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
    }
}
