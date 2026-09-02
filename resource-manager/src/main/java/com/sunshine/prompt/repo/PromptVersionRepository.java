package com.sunshine.prompt.repo;

import com.sunshine.prompt.entity.PromptVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromptVersionRepository extends JpaRepository<PromptVersionEntity, Long> {
    Optional<PromptVersionEntity> findByPromptIdAndVersion(String promptId, int version);
    List<PromptVersionEntity> findByPromptIdOrderByVersionDesc(String promptId);
    Optional<PromptVersionEntity> findTopByPromptIdOrderByVersionDesc(String promptId);
    Optional<PromptVersionEntity> findTopByPromptIdAndStatusOrderByVersionDesc(String promptId, String status);
}
