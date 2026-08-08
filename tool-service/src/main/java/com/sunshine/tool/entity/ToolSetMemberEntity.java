package com.sunshine.tool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "tool_set_member")
@IdClass(ToolSetMemberId.class)
@Getter
@Setter
public class ToolSetMemberEntity {

    @Id
    @Column(name = "set_id", nullable = false, length = 64)
    private String setId;

    @Id
    @Column(name = "tool_id", nullable = false, length = 128)
    private String toolId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean critical;
}
