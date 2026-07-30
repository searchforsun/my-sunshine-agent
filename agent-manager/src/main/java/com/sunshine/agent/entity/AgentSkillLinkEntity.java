package com.sunshine.agent.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "agent_skill_link")
@Getter
@Setter
public class AgentSkillLinkEntity {
    @EmbeddedId
    private AgentSkillLinkId id;
}
