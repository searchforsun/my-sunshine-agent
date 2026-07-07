package com.sunshine.orchestrator.taskboard;

import java.util.List;

/** Redis 会话级 TaskBoard 快照 */
public record ReactTaskBoardState(
        String boardId,
        String assistantMsgId,
        int revision,
        long updatedAt,
        List<TaskBoardItemView> items) {
}
