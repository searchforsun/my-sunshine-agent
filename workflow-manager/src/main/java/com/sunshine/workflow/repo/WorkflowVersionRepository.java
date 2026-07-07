package com.sunshine.workflow.repo;

import com.sunshine.workflow.entity.WorkflowVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersionEntity, Long> {

    List<WorkflowVersionEntity> findByTenantIdAndWorkflowIdOrderByVersionDesc(
            String tenantId, String workflowId);

    Optional<WorkflowVersionEntity> findByTenantIdAndWorkflowIdAndVersion(
            String tenantId, String workflowId, int version);
}
