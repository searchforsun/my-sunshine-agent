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
@Table(name = "execution_mode_policy")
@Getter
@Setter
public class ExecutionModePolicyEntity {

    @Id
    private String id;

    @Column(name = "mode_key", nullable = false, length = 32)
    private String modeKey;

    @Column(name = "tenant_id", length = 32)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_json", nullable = false)
    private Map<String, Object> policyJson;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
