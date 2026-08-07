package com.sunshine.oa.dto;

/** Admin 新建/更新 OA 待办请求；assigneeUserId 必填。 */
public record AdminTaskRequest(
        String assigneeUserId,
        String tenantId,
        String title,
        String category,
        String status
) {
}
