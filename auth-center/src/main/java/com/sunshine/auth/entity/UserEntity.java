package com.sunshine.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sys_user")
@Getter
@Setter
public class UserEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 32, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    @Column(length = 64)
    private String nickname;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(name = "default_write_hitl_mode", nullable = false, length = 16)
    private String defaultWriteHitlMode = "never";

    /** vertical|horizontal 侧栏平台/对话/任务排布 */
    @Column(name = "sidebar_sections_layout", nullable = false, length = 16)
    private String sidebarSectionsLayout = "vertical";

    /** 用户个人规则（soul），注入系统提示；null 表示未配置 */
    @Column(name = "personal_rules", columnDefinition = "TEXT")
    private String personalRules;

    @Column(name = "github_url", length = 255)
    private String githubUrl = "";

    @Column(name = "github_token", length = 255)
    private String githubToken = "";

    @Column(name = "gitlab_url", length = 255)
    private String gitlabUrl = "";

    @Column(name = "gitlab_token", length = 255)
    private String gitlabToken = "";

    @Column(nullable = false, columnDefinition = "TINYINT")
    private Byte status = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
