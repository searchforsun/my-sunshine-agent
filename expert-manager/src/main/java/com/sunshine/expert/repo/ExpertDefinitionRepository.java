package com.sunshine.expert.repo;

import com.sunshine.expert.entity.ExpertDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpertDefinitionRepository extends JpaRepository<ExpertDefinitionEntity, String> {
    List<ExpertDefinitionEntity> findByEnabledTrueOrderByIdAsc();
}
