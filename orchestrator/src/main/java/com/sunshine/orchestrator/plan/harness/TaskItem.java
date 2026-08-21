package com.sunshine.orchestrator.plan.harness;

import java.util.List;

/**
 * H1 可调度粗单元；status: pending|in_progress|done|fail|cancelled|obsolete。
 * <p>
 * v17.7 版本化：同一 baseTask 失败/取消后重派生成新 taskId（t1-1 -> t1-2 -> t1-3），
 * 保留历史记录；retryIndex 从 1 起，最多 3 次（重试 2 次）。
 */
public record TaskItem(
        String taskId,
        String label,
        String status,
        List<String> dependsOn,
        String constraints,
        String expectedOutput,
        String successCriteria,
        String baseTaskId,
        int retryIndex,
        String parentTaskId,
        String failReason) {

    /** 旧构造兼容（无版本化字段） */
    public TaskItem(
            String taskId,
            String label,
            String status,
            List<String> dependsOn,
            String constraints,
            String expectedOutput,
            String successCriteria) {
        this(taskId, label, status, dependsOn, constraints, expectedOutput, successCriteria,
                null, 0, null, null);
    }

    /** 首次创建：baseTaskId = taskId 去版本后缀，retryIndex = 1 */
    public static TaskItem initial(
            String taskId,
            String label,
            List<String> dependsOn,
            String constraints,
            String expectedOutput,
            String successCriteria) {
        String base = stripRetrySuffix(taskId);
        return new TaskItem(taskId, label, "pending", dependsOn, constraints, expectedOutput, successCriteria,
                base, 1, null, null);
    }

    /** 去除 retryIndex 后缀（t1-2 -> t1），用于 baseTaskId 提取 */
    static String stripRetrySuffix(String taskId) {
        if (taskId == null) {
            return null;
        }
        int dash = taskId.lastIndexOf('-');
        if (dash <= 0) {
            return taskId;
        }
        String suffix = taskId.substring(dash + 1);
        try {
            Integer.parseInt(suffix);
            return taskId.substring(0, dash);
        } catch (NumberFormatException e) {
            return taskId;
        }
    }

    /**
     * 展示用版本化 id：无 {@code -N} 后缀时补 {@code -retryIndex}（t1 → t1-1），已有后缀原样。
     * 用于 TaskBoard 投影与 Worker 卡 parentStepId，保证首次执行就显示 T1-1、重派 T1-2，可区分尝试次数。
     */
    public String versionedId() {
        if (taskId == null) {
            return null;
        }
        int dash = taskId.lastIndexOf('-');
        if (dash > 0) {
            try {
                Integer.parseInt(taskId.substring(dash + 1));
                return taskId;
            } catch (NumberFormatException ignored) {
                // 非数字后缀（如 t1-arch）：继续补版本
            }
        }
        return taskId + "-" + Math.max(1, retryIndex);
    }

    /** 同 baseTask 下一次重试的 taskId（t1-2 -> t1-3） */
    static String nextRetryTaskId(String currentTaskId) {
        if (currentTaskId == null) {
            return null;
        }
        int dash = currentTaskId.lastIndexOf('-');
        if (dash <= 0) {
            return currentTaskId + "-2";
        }
        String suffix = currentTaskId.substring(dash + 1);
        try {
            int idx = Integer.parseInt(suffix);
            return currentTaskId.substring(0, dash) + "-" + (idx + 1);
        } catch (NumberFormatException e) {
            return currentTaskId + "-2";
        }
    }

    /** v17.7 硬控制：同一 baseTask 最多执行 3 次（重试 2 次） */
    static final int MAX_RETRY_INDEX = 3;

    /** 复制并改状态（保留 failReason） */
    public TaskItem withStatus(String status, String failReason) {
        return new TaskItem(
                taskId, label, status, dependsOn, constraints, expectedOutput, successCriteria,
                baseTaskId, retryIndex, parentTaskId, failReason);
    }
}
