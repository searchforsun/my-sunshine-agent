package com.sunshine.prompt.repo;

import com.sunshine.prompt.entity.PromptDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptDefinitionRepository extends JpaRepository<PromptDefinitionEntity, String> {
}
