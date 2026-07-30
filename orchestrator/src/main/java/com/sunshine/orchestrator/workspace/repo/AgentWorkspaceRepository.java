package com.sunshine.orchestrator.workspace.repo;

import com.sunshine.orchestrator.workspace.entity.AgentWorkspaceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentWorkspaceRepository extends JpaRepository<AgentWorkspaceEntity, String> {
    List<AgentWorkspaceEntity> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, String status);
}
