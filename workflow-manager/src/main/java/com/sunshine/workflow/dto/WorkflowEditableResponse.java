package com.sunshine.workflow.dto;

import java.util.Map;

/** Studio 编辑态 — 最新 draft 或当前 published */
public record WorkflowEditableResponse(
        String workflowId,
        int version,
        String status,
        Map<String, Object> plan,
        Map<String, Object> catalog) {
}
