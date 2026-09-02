package com.sunshine.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_definition")
@Getter
@Setter
public class AgentDefinitionEntity {
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
    @Column(name = "tenant_id", length = 32, nullable = false)
    private String tenantId = "default";
    @Column(name = "tags_json", length = 512, nullable = false)
    private String tagsJson = "[]";
    @Column(name = "tools_json", length = 512, nullable = false)
    private String toolsJson = "[]";
    @Column(name = "kb_scope_json", length = 512, nullable = false)
    private String kbScopeJson = "[]";
    @Column(name = "data_scope_json", columnDefinition = "TEXT")
    private String dataScopeJson;
    @Column(name = "permissions_json", length = 512, nullable = false)
    private String permissionsJson = "{}";
    @Column(name = "model_config_json", length = 512, nullable = false)
    private String modelConfigJson = "{}";
    @Column(name = "max_iters", nullable = false)
    private int maxIters = 2;
    @Column(name = "max_handoffs", nullable = false)
    private int maxHandoffs = 5;
    @Column(length = 16, nullable = false)
    private String kind = "all";
    @Column(name = "biz_scene", length = 64)
    private String bizScene;
    @Column(name = "source", length = 16, nullable = false)
    private String source = "INTERNAL";
    @Column(name = "agent_card_url", length = 512)
    private String agentCardUrl;
    @Column(name = "auth_config_json", length = 512)
    private String authConfigJson;
    @Column(name = "endpoint_override", length = 512)
    private String endpointOverride;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
