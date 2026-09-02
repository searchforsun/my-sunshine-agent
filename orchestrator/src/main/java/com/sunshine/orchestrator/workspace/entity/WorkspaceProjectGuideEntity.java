package com.sunshine.orchestrator.workspace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 项目级规范（类 CLAUDE.md）：用户手动维护，随工作区共享，注入 task 场景上下文。 */
@Entity
@Table(name = "workspace_project_guide")
@Getter
@Setter
public class WorkspaceProjectGuideEntity {

    @Id
    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId = "default";

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
