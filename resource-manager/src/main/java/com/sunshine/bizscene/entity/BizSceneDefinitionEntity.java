package com.sunshine.bizscene.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "biz_scene_definition")
@Getter
@Setter
public class BizSceneDefinitionEntity {

    @Id
    @Column(name = "biz_scene", length = 64)
    private String bizScene;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(length = 512)
    private String description;

    @Column(nullable = false, length = 16)
    private String status = "active";

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** description 的 embedding 向量（JSON float[]，由 orchestrator 场景 embedding 服务回填）。 */
    @Column(name = "description_vector", columnDefinition = "json")
    private String descriptionVector;

    /** manual=运营预定义 | auto=大模型自动发现（authority §2.1c）。 */
    @Column(nullable = false, length = 16)
    private String source = "manual";

    /** auto 场景的首次触发会话（溯源）。 */
    @Column(name = "source_conversation_id", length = 64)
    private String sourceConversationId;

    /** 审核人（auto 场景升 active 时记录）。 */
    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    /** 审核时间。 */
    @Column(name = "approved_at")
    private Instant approvedAt;
}
