package com.sunshine.orchestrator.workspace.repo;

import com.sunshine.orchestrator.workspace.entity.WorkspaceProjectGuideEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceProjectGuideRepository
        extends JpaRepository<WorkspaceProjectGuideEntity, String> {
}
