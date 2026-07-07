package com.sunshine.orchestrator.taskboard;

/** TaskBoard SSE / metadata 视图 */
public record TaskBoardItemView(String id, String content, String status) {
}
