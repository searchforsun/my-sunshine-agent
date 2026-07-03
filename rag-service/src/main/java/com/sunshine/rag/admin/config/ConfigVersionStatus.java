package com.sunshine.rag.admin.config;

/** 知识库配置版本状态（每 kb 独立版本链） */
public final class ConfigVersionStatus {
    public static final String DRAFT = "draft";
    public static final String PENDING_EVAL = "pending_eval";
    public static final String EVALUATING = "evaluating";
    public static final String EVAL_PASSED = "eval_passed";
    public static final String EVAL_FAILED = "eval_failed";
    public static final String ACTIVE = "active";
    public static final String SUPERSEDED = "superseded";

    private ConfigVersionStatus() {
    }

    public static boolean isDraft(String status) {
        return DRAFT.equals(status);
    }

    public static boolean canApplySuggestions(String status) {
        return EVAL_FAILED.equals(status);
    }

    public static boolean canEdit(String status) {
        return isDraft(status);
    }

    public static boolean canApply(String status) {
        return !isDraft(status);
    }

    public static boolean canRevertToDraft(String status) {
        return PENDING_EVAL.equals(status) || EVAL_PASSED.equals(status) || EVAL_FAILED.equals(status);
    }

    public static boolean isEvaluating(String status) {
        return EVALUATING.equals(status);
    }

    public static boolean canBeginEval(String status) {
        return PENDING_EVAL.equals(status) || EVAL_FAILED.equals(status) || EVAL_PASSED.equals(status);
    }

    public static boolean locksStatusChange(String status) {
        return isEvaluating(status);
    }

    public static boolean isActive(String status) {
        return ACTIVE.equals(status);
    }

    public static boolean isPipelineStatus(String status) {
        return isDraft(status)
                || PENDING_EVAL.equals(status)
                || EVALUATING.equals(status)
                || EVAL_PASSED.equals(status)
                || EVAL_FAILED.equals(status);
    }

    public static boolean canCopyToDraft(String status) {
        return isActive(status) || SUPERSEDED.equals(status);
    }
}
