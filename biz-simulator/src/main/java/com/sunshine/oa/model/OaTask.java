package com.sunshine.oa.model;

/** OA 待办任务（归属校验按 tenant + assigneeUserId）。 */
public record OaTask(
        String id,
        String title,
        String category,
        String status,
        String assigneeUserId
) {
}
