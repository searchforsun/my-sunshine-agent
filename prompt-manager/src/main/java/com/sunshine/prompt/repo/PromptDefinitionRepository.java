package com.sunshine.prompt.repo;

import com.sunshine.prompt.entity.PromptDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PromptDefinitionRepository extends JpaRepository<PromptDefinitionEntity, String> {
    List<PromptDefinitionEntity> findByKind(String kind);
    List<PromptDefinitionEntity> findByEnabled(boolean enabled);
    List<PromptDefinitionEntity> findByKindAndEnabled(String kind, boolean enabled);
}
