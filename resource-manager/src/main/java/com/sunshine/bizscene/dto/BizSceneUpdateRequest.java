package com.sunshine.bizscene.dto;

/**
 * 场景更新请求。{@code status} 可含 active/disabled/pending_review/rejected/auto_cleaned（v4）；
 * {@code approvedBy} 非空且 status→active 时记录审核人/时间（auto 场景升正式）。
 */
public record BizSceneUpdateRequest(
        String displayName,
        String description,
        String status,
        String approvedBy
) {
}
