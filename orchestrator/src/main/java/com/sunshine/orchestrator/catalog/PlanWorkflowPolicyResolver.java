package com.sunshine.orchestrator.catalog;

import com.sunshine.common.tool.PlanWorkflowExecutionPolicy;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlanWorkflowPolicyResolver {

    private final AgentExecutionProperties agentExecutionProperties;

    public PlanWorkflowExecutionPolicy resolve(String tenantId) {
        AgentExecutionProperties.PlanWorkflow plan = agentExecutionProperties.getPlanWorkflow();
        if (plan == null || plan.getNodeRetry() == null) {
            return PlanWorkflowExecutionPolicy.platformDefault();
        }
        return plan.getNodeRetry().toPolicy();
    }
}
