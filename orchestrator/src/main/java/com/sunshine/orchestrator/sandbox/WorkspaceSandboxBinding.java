package com.sunshine.orchestrator.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceSandboxBinding(
        String sessionId, String userId, String tenantId, String workspaceId,
        String state, Long lastActiveAt,
        String repoUrl, String repoBranch, String cloneState,
        int memoryMb, double cpus, String image) {

    public static final String STATE_RUNNING = "running";
    public static final String STATE_STOPPED = "stopped";

    public WorkspaceSandboxBinding {
        if (state == null || state.isBlank()) {
            state = STATE_RUNNING;
        }
    }

    @JsonIgnore
    public boolean isStopped() {
        return STATE_STOPPED.equalsIgnoreCase(state);
    }

    public WorkspaceSandboxBinding withState(String newState) {
        return new WorkspaceSandboxBinding(
                sessionId, userId, tenantId, workspaceId, newState,
                lastActiveAt, repoUrl, repoBranch, cloneState, memoryMb, cpus, image);
    }

    public WorkspaceSandboxBinding withCloneState(String newCloneState) {
        return new WorkspaceSandboxBinding(
                sessionId, userId, tenantId, workspaceId, state,
                lastActiveAt, repoUrl, repoBranch, newCloneState, memoryMb, cpus, image);
    }
}
