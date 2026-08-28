package com.sunshine.orchestrator.taskboard;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.TimelineStepId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * TaskBoard 终态收口与进度文案。
 *
 * <p>任务列表唯一数据源是原生 {@code todo_write} 写入的 {@code AgentState.tasksContext}
 * （随 checkpoint 持久化），终态从这里读并落 MySQL 审计 + 完成 timeline tasks 步。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskBoardService {

    private final TaskBoardTimelineSupport timelineSupport;
    private final TaskBoardAuditService auditService;

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
        TaskBoardState state = new TaskBoardState(
                "native-" + msgId, msgId, revision, System.currentTimeMillis(), items);
        auditService.persistFinal(state);
        if (session.hasStep(TimelineStepId.TASKS.id())) {
            timelineSupport.completeOnRunEnd(session, items, revision, progressSummary(items));
        } else {
            timelineSupport.applyUpdate(session, items, revision, progressSummary(items));
            timelineSupport.completeOnRunEnd(session, items, revision, progressSummary(items));
        }
    }

    /**
     * O1 中断落板：仅写 MySQL 快照（幂等：按 messageId upsert），不做 timeline 收口。
     * 中断时流与 hookQueue 均无消费者，恢复块读的是最近快照。
     */
    public void persistInterruptSnapshot(
            com.sunshine.orchestrator.agent.runtime.AgentRunRequest request,
            io.agentscope.core.state.AgentState agentState) {
        if (request == null || request.assistantMessageId() == null
                || request.assistantMessageId().isBlank()) {
            return;
        }
        List<TaskBoardItemView> items = agentState != null && agentState.getTasksContext() != null
                ? TodoTasksBridge.toItems(agentState.getTasksContext().getTasks())
                : List.of();
        if (items.isEmpty()) {
            return;
        }
        String msgId = request.assistantMessageId();
        auditService.persistFinal(new TaskBoardState(
                "native-" + msgId, msgId, items.size(), System.currentTimeMillis(), items));
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

    static String renderTaskListBlock(List<TaskBoardItemView> items) {
        StringBuilder sb = new StringBuilder("【任务板】");
        sb.append('\n').append("进度：").append(progressSummary(items));
        for (TaskBoardItemView item : items) {
            if (item == null || !StringUtils.hasText(item.content())) {
                continue;
            }
            sb.append('\n').append("- [")
                    .append(StringUtils.hasText(item.status()) ? item.status().strip() : "pending")
                    .append("] ")
                    .append(item.content().strip());
        }
        sb.append("\n接着未完成项继续；勿重建整个任务板，勿把已完成项改回待办。");
        return sb.toString();
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
