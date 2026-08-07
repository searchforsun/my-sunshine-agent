package com.sunshine.agent.repo;

import com.sunshine.agent.entity.AgentDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentDefinitionRepository extends JpaRepository<AgentDefinitionEntity, String> {
    List<AgentDefinitionEntity> findByEnabledTrueOrderByIdAsc();
}
