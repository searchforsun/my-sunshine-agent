package com.sunshine.oa.dto;

/** Admin CRUD：OA 待办（含归属 assigneeUserId）。 */
public record AdminTaskVO(
        String id,
        String tenantId,
        String assigneeUserId,
        String title,
        String category,
        String status
) {
}
