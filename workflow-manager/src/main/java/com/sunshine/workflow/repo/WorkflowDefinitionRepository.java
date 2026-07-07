package com.sunshine.workflow.repo;

import com.sunshine.workflow.entity.WorkflowDefinitionEntity;
import com.sunshine.workflow.entity.WorkflowDefinitionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinitionEntity, WorkflowDefinitionId> {

    List<WorkflowDefinitionEntity> findByPkTenantIdOrderByUpdatedAtDesc(String tenantId);
}
