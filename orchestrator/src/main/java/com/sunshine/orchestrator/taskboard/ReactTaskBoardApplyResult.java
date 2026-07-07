package com.sunshine.orchestrator.taskboard;

import java.util.List;

/** manage_tasks 工具返回 / 服务层结果 */
public record ReactTaskBoardApplyResult(
        boolean ok,
        String error,
        int revision,
        String summary,
        List<TaskBoardItemView> items) {

    public static ReactTaskBoardApplyResult success(int revision, String summary, List<TaskBoardItemView> items) {
        return new ReactTaskBoardApplyResult(true, null, revision, summary, List.copyOf(items));
    }

    public static ReactTaskBoardApplyResult failure(String error) {
        return new ReactTaskBoardApplyResult(false, error, 0, null, List.of());
    }
}
