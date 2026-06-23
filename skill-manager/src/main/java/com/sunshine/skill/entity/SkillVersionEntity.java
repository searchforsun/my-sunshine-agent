package com.sunshine.skill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "skill_version")
@Getter
@Setter
public class SkillVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_id", nullable = false)
    private String skillId;

    @Column(nullable = false)
    private int version;

    @Column(name = "system_overlay", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String systemOverlay;

    @Column(name = "tools_json", nullable = false)
    private String toolsJson = "[]";

    @Column(name = "max_iters", nullable = false)
    private int maxIters = 4;

    @Column(name = "side_effect", nullable = false)
    private String sideEffect = "read";

    @Column(name = "sandbox", nullable = false)
    private String sandbox = "none";

    @Column(name = "references_json", nullable = false)
    private String referencesJson = "[]";

    @Column(name = "scripts_json", nullable = false)
    private String scriptsJson = "[]";

    @Column(name = "storage_path")
    private String storagePath;

    @Column(nullable = false)
    private String status = "published";

    /** 维护人 userId — 展示名由 BFF 关联 auth-center 查询 */
    @Column(length = 64)
    private String maintainer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
