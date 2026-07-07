package com.sunshine.workflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** workflow_definition 复合主键 (tenant_id, id) */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class WorkflowDefinitionId implements Serializable {

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";

    @Column(name = "id", length = 64, nullable = false)
    private String id;

    public WorkflowDefinitionId(String tenantId, String id) {
        this.tenantId = tenantId != null ? tenantId : "default";
        this.id = id;
    }
}
