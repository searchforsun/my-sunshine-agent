package com.sunshine.agent.repo;

import com.sunshine.agent.entity.AgentSkillLinkEntity;
import com.sunshine.agent.entity.AgentSkillLinkId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentSkillLinkRepository extends JpaRepository<AgentSkillLinkEntity, AgentSkillLinkId> {
    List<AgentSkillLinkEntity> findByIdExpertIdOrderByIdSkillIdAsc(String expertId);
    void deleteByIdExpertId(String expertId);
}
