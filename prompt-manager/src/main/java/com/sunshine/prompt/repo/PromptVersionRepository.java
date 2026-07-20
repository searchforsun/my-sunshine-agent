package com.sunshine.prompt.repo;

import com.sunshine.prompt.entity.PromptVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptVersionRepository extends JpaRepository<PromptVersionEntity, Long> {
}
