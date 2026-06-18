package com.sunshine.oa.dto;

/** OA 待办任务 Mock 视图 */
public record OaTaskVO(
        long id,
        String title,
        String category,
        String status,
        String assignee,
        String dueDate
) {
}
