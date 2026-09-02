package com.sunshine.skill.repo;

import com.sunshine.skill.entity.SkillDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillDefinitionRepository extends JpaRepository<SkillDefinitionEntity, String> {

    List<SkillDefinitionEntity> findByEnabledTrueOrderByIdAsc();
}
