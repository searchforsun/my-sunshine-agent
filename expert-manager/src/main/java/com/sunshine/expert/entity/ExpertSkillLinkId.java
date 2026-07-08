package com.sunshine.expert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Embeddable
@Getter
@Setter
@EqualsAndHashCode
public class ExpertSkillLinkId implements Serializable {
    @Column(name = "expert_id", length = 64)
    private String expertId;
    @Column(name = "skill_id", length = 64)
    private String skillId;
}
