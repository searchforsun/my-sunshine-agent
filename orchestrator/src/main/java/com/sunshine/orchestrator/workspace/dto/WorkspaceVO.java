package com.sunshine.orchestrator.workspace.dto;

import com.sunshine.orchestrator.workspace.entity.AgentWorkspaceEntity;

import java.time.Instant;

public record WorkspaceVO(
        String id, String name, String repoUrl, String repoBranch,
        String sandboxProfile, int memoryMb, double cpus, String image,
        String status, String cloneState, Instant createdAt) {

    public static WorkspaceVO from(AgentWorkspaceEntity e, String cloneState) {
        return new WorkspaceVO(e.getId(), e.getName(), e.getRepoUrl(), e.getRepoBranch(),
                e.getSandboxProfile(), e.getMemoryMb(), e.getCpus().doubleValue(), e.getImage(),
                e.getStatus(), cloneState, e.getCreatedAt());
    }
}
