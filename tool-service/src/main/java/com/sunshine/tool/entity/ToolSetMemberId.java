package com.sunshine.tool.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ToolSetMemberId implements Serializable {

    private String setId;
    private String toolId;
}
