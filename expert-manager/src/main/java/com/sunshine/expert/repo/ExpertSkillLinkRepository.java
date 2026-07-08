package com.sunshine.expert.repo;

import com.sunshine.expert.entity.ExpertSkillLinkEntity;
import com.sunshine.expert.entity.ExpertSkillLinkId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpertSkillLinkRepository extends JpaRepository<ExpertSkillLinkEntity, ExpertSkillLinkId> {
    List<ExpertSkillLinkEntity> findByIdExpertIdOrderByIdSkillIdAsc(String expertId);
    void deleteByIdExpertId(String expertId);
}
