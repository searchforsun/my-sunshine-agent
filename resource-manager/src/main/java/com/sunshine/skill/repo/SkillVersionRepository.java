package com.sunshine.skill.repo;

import com.sunshine.skill.entity.SkillVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkillVersionRepository extends JpaRepository<SkillVersionEntity, Long> {

    Optional<SkillVersionEntity> findBySkillIdAndVersion(String skillId, int version);

    List<SkillVersionEntity> findBySkillIdOrderByVersionDesc(String skillId);

    Optional<SkillVersionEntity> findTopBySkillIdOrderByVersionDesc(String skillId);

    Optional<SkillVersionEntity> findFirstBySkillIdAndStatusOrderByVersionDesc(String skillId, String status);
}
