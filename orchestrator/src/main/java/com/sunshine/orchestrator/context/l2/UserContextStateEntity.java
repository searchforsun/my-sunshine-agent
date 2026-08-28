package com.sunshine.orchestrator.context.l2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** L2 跨会话结构化状态条目 */
@Entity
@Table(name = "user_context_state")
@Getter
@Setter
public class UserContextStateEntity {

    @Id
    @Column(length = 32)
    private String id;

    @Column(nullable = false, length = 16)
    private String scope = "user";

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(name = "state_key", nullable = false, length = 128)
    private String stateKey;

    @Column(name = "state_value", nullable = false, columnDefinition = "TEXT")
    private String stateValue;

    @Column(length = 256)
    private String background;

    /**
     * 场景偏好作用域（authority §4.3）：{@code *} = 全局；或具体 {@code biz_scene}。
     * 偏好装载经 biz_scene 白名单过滤；存量行为 {@code *}（全局）。
     */
    @Column(name = "biz_scene_scope", length = 64)
    private String bizSceneScope = "*";

    /**
     * 偏好确认态（authority §4.3）：{@code confirmed}（默认可装载）| {@code inferred}（默认不装载）。
     */
    @Column(name = "confirm_status", length = 16)
    private String confirmStatus = "confirmed";

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false, length = 16)
    private String status = "active";

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "source_msg_id", length = 64)
    private String sourceMsgId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
