package com.sunshine.expert.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "expert_skill_link")
@Getter
@Setter
public class ExpertSkillLinkEntity {
    @EmbeddedId
    private ExpertSkillLinkId id;
}
