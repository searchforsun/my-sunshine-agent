package com.sunshine.tool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "tool_definition")
@Getter
@Setter
public class ToolDefinitionEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "source_ref", nullable = false, length = 64)
    private String sourceRef;

    @Column(name = "external_name", nullable = false, length = 128)
    private String externalName;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_json", nullable = false)
    private Map<String, Object> schemaJson;

    @Column(name = "schema_hash", length = 64)
    private String schemaHash;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "timeline_summary_template", nullable = false, length = 512)
    private String timelineSummaryTemplate = "";

    @Column(name = "timeline_summary_extract", columnDefinition = "TEXT")
    private String timelineSummaryExtract;

    @Column(name = "side_effect", nullable = false, length = 16)
    private String sideEffect = "read";

    @Column(name = "require_confirmation", nullable = false)
    private boolean requireConfirmation;

    @Column(name = "confirmation_edited", nullable = false)
    private boolean confirmationEdited;

    @Column(name = "tenant_id", nullable = false, length = 32)
    private String tenantId = "default";

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "metadata_edited", nullable = false)
    private boolean metadataEdited;

    /** Catalog ID 是否符合 LLM_SAFE（含 __ 拼接、无 '.'） */
    @Column(name = "id_valid", nullable = false)
    private boolean idValid = true;

    @Column(name = "id_error", length = 512)
    private String idError;

    @Column(name = "discovered_at")
    private Instant discoveredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
