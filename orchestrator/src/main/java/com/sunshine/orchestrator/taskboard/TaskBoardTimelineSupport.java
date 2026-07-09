package com.sunshine.orchestrator.taskboard;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.TaskBoardStepLabels;
import com.sunshine.orchestrator.processing.ThinkStepIds;
import com.sunshine.orchestrator.processing.TimelineStepId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@code tasks} 聚合步 lifecycle + metadata */
@Component
@RequiredArgsConstructor
public class TaskBoardTimelineSupport {

    /** 首轮规划推理结束后立即占位，manage_tasks 到达后再填充 metadata */
    public void ensurePlaceholderAfterFirstThink(ProcessingTimelineSession session) {
        if (session == null || session.hasStep(TimelineStepId.TASKS.id())) {
            return;
        }
        String thinkId = session.lastCompletedThinkId();
        if (!ThinkStepIds.forIteration(1).equals(thinkId)) {
            return;
        }
        String stepId = TimelineStepId.TASKS.id();
        String phase = TimelineStepId.TASKS.phase();
        session.updateTaskBoard(stepId, phase, TaskBoardStepLabels.before(), null);
    }

    public void applyUpdate(
            ProcessingTimelineSession session,
            List<TaskBoardItemView> items,
            int revision,
            String taskProgress) {
        if (session == null || items == null || items.isEmpty()) {
            return;
        }
        String stepId = TimelineStepId.TASKS.id();
        String phase = TimelineStepId.TASKS.phase();
        String activeTask = ReactTaskBoardService.findActiveTask(items);
        StepMetadata metadata = StepMetadata.withTasks(items, revision, taskProgress);
        session.updateTaskBoard(stepId, phase, TaskBoardStepLabels.active(activeTask), metadata);
    }

    public void completeOnRunEnd(
            ProcessingTimelineSession session,
            List<TaskBoardItemView> items,
            int revision,
            String taskProgress) {
        if (session == null || !session.hasStep(TimelineStepId.TASKS.id())) {
            return;
        }
        boolean allDone = ReactTaskBoardService.allTerminal(items);
        String after = allDone ? TaskBoardStepLabels.allDone() : TaskBoardStepLabels.after();
        StepMetadata metadata = items != null && !items.isEmpty()
                ? StepMetadata.withTasks(items, revision, taskProgress)
                : null;
        session.completeTaskBoard(after, metadata);
    }
}
