package com.sunshine.orchestrator.workflow;

public record WorkflowBindingOutcome(
        boolean bound,
        boolean unknown,
        String workflowId,
        String effectiveQuery
) {
    public static WorkflowBindingOutcome none(String query) {
        return new WorkflowBindingOutcome(false, false, null, query != null ? query : "");
    }

    public static WorkflowBindingOutcome unknown(String token) {
        return new WorkflowBindingOutcome(false, true, token, "");
    }

    public static WorkflowBindingOutcome bound(String workflowId, String effectiveQuery) {
        return new WorkflowBindingOutcome(true, false, workflowId, effectiveQuery);
    }
}
