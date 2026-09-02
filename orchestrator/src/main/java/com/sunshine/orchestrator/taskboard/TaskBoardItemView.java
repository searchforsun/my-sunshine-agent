package com.sunshine.orchestrator.taskboard;

import java.util.List;

/** TaskBoard SSE / metadata 视图 */
public record TaskBoardItemView(
        String id, String content, String status,
        List<String> dependsOn) {
    public TaskBoardItemView(String id, String content, String status) {
        this(id, content, status, null);
    }
}
