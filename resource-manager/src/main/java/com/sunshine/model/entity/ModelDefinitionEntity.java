package com.sunshine.model.entity;

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
@Table(name = "model_definition")
@Getter
@Setter
public class ModelDefinitionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "provider_key", length = 64, nullable = false)
    private String providerKey;
    @Column(name = "model_name", length = 128, nullable = false)
    private String modelName;
    @Column(name = "display_name", length = 128, nullable = false)
    private String displayName;
    @Column(name = "context_window", nullable = false)
    private int contextWindow = 32768;
    /** 单次补全 max_tokens 上限（上游模型约束，如 qwen-max=8192） */
    @Column(name = "max_output_tokens", nullable = false)
    private int maxOutputTokens = 8192;
    @Column(length = 32, nullable = false)
    private String encoding = "cl100k_base";
    /** JSON：reasoning / multimodal / tool_call|toolCall */
    @Column(nullable = false, columnDefinition = "json")
    private String capabilities;
    /** OpenAI 兼容请求缺省参数 JSON（reasoning_split / temperature 等） */
    @Column(name = "request_extras", columnDefinition = "json")
    private String requestExtras;
    @Column(name = "user_selectable", nullable = false)
    private boolean userSelectable;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
