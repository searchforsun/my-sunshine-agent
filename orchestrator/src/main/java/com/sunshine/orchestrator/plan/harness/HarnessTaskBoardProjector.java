package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import java.util.ArrayList;
import java.util.List;

/** H1 taskQueue → TaskBoard 视图投影（status 枚举映射；不改写 label）。 */
public final class HarnessTaskBoardProjector {
    private HarnessTaskBoardProjector() {
    }

    public static List<TaskBoardItemView> project(PlanNotebook notebook) {
        List<TaskBoardItemView> views = new ArrayList<>();
        if (notebook == null || notebook.getTaskQueue() == null) {
            return views;
        }
        for (TaskItem item : notebook.getTaskQueue()) {
            List<String> dependsOn = item.dependsOn();
            if (dependsOn == null || dependsOn.isEmpty()) {
                dependsOn = null;
            }
            views.add(new TaskBoardItemView(
                    item.taskId(),
                    item.label(),
                    mapStatus(item.status()),
                    dependsOn));
        }
        return views;
    }

    private static String mapStatus(String status) {
        if (status == null) {
            return "pending";
        }
        return switch (status) {
            case "pending" -> "pending";
            case "in_progress" -> "in_progress";
            case "done" -> "completed";
            case "fail", "obsolete" -> "cancelled";
            default -> status;
        };
    }
}
