package com.sunshine.orchestrator.plan.harness;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** harness 线性 task 队列轻量结构校验（S7：不复用 PlanValidator）。 */
public final class TaskQueueValidator {
    private TaskQueueValidator() {
    }

    public static Optional<String> validate(List<TaskItem> items) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        Map<String, TaskItem> byId = new HashMap<>();
        for (TaskItem item : items) {
            if (item.taskId() == null || item.taskId().isBlank()) {
                return Optional.of("task id must not be blank");
            }
            if (item.label() == null || item.label().isBlank()) {
                return Optional.of("task label must not be blank: " + item.taskId());
            }
            byId.put(item.taskId(), item);
        }
        for (TaskItem item : items) {
            List<String> dependsOn = item.dependsOn();
            if (dependsOn == null) {
                continue;
            }
            for (String dep : dependsOn) {
                if (dep == null || dep.isBlank()) {
                    return Optional.of("dependsOn must not contain blank ref: " + item.taskId());
                }
                if (!byId.containsKey(dep)) {
                    return Optional.of("dependsOn references missing task: " + dep);
                }
            }
        }
        if (hasCycle(byId)) {
            return Optional.of("dependsOn contains a cycle");
        }
        return Optional.empty();
    }

    private static boolean hasCycle(Map<String, TaskItem> byId) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String taskId : byId.keySet()) {
            if (detectCycle(taskId, byId, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean detectCycle(
            String taskId,
            Map<String, TaskItem> byId,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(taskId)) {
            return false;
        }
        if (!visiting.add(taskId)) {
            return true;
        }
        TaskItem item = byId.get(taskId);
        List<String> dependsOn = item.dependsOn();
        if (dependsOn != null) {
            for (String dep : dependsOn) {
                if (detectCycle(dep, byId, visiting, visited)) {
                    return true;
                }
            }
        }
        visiting.remove(taskId);
        visited.add(taskId);
        return false;
    }
}
