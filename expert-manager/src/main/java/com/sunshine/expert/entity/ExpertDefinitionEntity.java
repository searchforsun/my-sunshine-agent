package com.sunshine.expert.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "expert_definition")
@Getter
@Setter
public class ExpertDefinitionEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;
    @Column(length = 512)
    private String description;
    @Column(name = "system_prompt", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String systemPrompt;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "tags_json", length = 512, nullable = false)
    private String tagsJson = "[]";
    @Column(name = "tools_json", length = 512, nullable = false)
    private String toolsJson = "[\"*\"]";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
