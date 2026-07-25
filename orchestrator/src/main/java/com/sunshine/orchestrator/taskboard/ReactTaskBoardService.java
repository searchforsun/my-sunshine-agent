package com.sunshine.orchestrator.taskboard;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.TimelineStepId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TaskBoard 终态收口与进度文案。
 *
 * <p>任务列表唯一数据源是原生 {@code todo_write} 写入的 {@code AgentState.tasksContext}
 * （随 checkpoint 持久化），终态从这里读并落 MySQL 审计 + 完成 timeline tasks 步。</p>
 */
@Service
@RequiredArgsConstructor
public class ReactTaskBoardService {

    private final TaskBoardTimelineSupport timelineSupport;
    private final ReactTaskBoardAuditService auditService;

    /**
     * 原生 TaskList 终态收口：从 AgentState.tasksContext 读任务列表，
     * 完成 timeline tasks 步 + 终态落 MySQL 审计。
     */
    public void finalizeNativeTimeline(
            ProcessingTimelineSession session,
            com.sunshine.orchestrator.agent.runtime.AgentRunRequest request,
            io.agentscope.core.state.AgentState agentState) {
        if (session == null || request == null || request.assistantMessageId() == null
                || request.assistantMessageId().isBlank()) {
            return;
        }
        List<TaskBoardItemView> items = agentState != null && agentState.getTasksContext() != null
                ? TodoTasksBridge.toItems(agentState.getTasksContext().getTasks())
                : List.of();
        if (items.isEmpty()) {
            if (session.hasStep(TimelineStepId.TASKS.id())) {
                timelineSupport.dismissEmptyPlaceholder(session);
            }
            return;
        }
        String msgId = request.assistantMessageId();
        int revision = items.size();
        ReactTaskBoardState state = new ReactTaskBoardState(
                "native-" + msgId, msgId, revision, System.currentTimeMillis(), items);
        auditService.persistFinal(state);
        if (session.hasStep(TimelineStepId.TASKS.id())) {
            timelineSupport.completeOnRunEnd(session, items, revision, progressSummary(items));
        } else {
            timelineSupport.applyUpdate(session, items, revision, progressSummary(items));
            timelineSupport.completeOnRunEnd(session, items, revision, progressSummary(items));
        }
    }

    static String progressSummary(List<TaskBoardItemView> items) {
        if (items == null || items.isEmpty()) {
            return "0/0 已完成";
        }
        long completed = items.stream().filter(i -> "completed".equals(i.status())).count();
        return completed + "/" + items.size() + " 已完成";
    }

    static boolean allTerminal(List<TaskBoardItemView> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        return items.stream().allMatch(i ->
                "completed".equals(i.status()) || "cancelled".equals(i.status()));
    }

    static String findActiveTask(List<TaskBoardItemView> items) {
        if (items == null) {
            return "";
        }
        return items.stream()
                .filter(i -> "in_progress".equals(i.status()))
                .map(TaskBoardItemView::content)
                .findFirst()
                .orElse("");
    }
}
