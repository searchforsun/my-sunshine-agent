package com.sunshine.orchestrator.taskboard;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.Task;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * P3 原生 TaskList → timeline tasks 步桥接。
 *
 * <p>原生 {@code todo_write} 写 {@link AgentState#getTasksContext()}（随 checkpoint 持久化），
 * 本桥把其 {@link Task} 列表映射为 timeline 的 {@link TaskBoardItemView}（id/content/status），
 * 复用 {@link TaskBoardTimelineSupport} 投影到单一 {@code tasks} 步，前端零改。
 */
@Slf4j
public final class TodoTasksBridge {

    /** 原生 todo_write 工具名（AgentScope TodoTools） */
    public static final String TODO_WRITE = "todo_write";

    private TodoTasksBridge() {
    }

    public static boolean isTodoWrite(String toolName) {
        return TODO_WRITE.equals(toolName);
    }

    /** 从当前 AgentState.tasksContext 读任务列表，投影到 timeline tasks 步 */
    public static void emitTodoTasks(
            Agent agent, RuntimeContext ctx, String bridgeId,
            TaskBoardTimelineSupport timelineSupport) {
        if (timelineSupport == null) {
            return;
        }
        List<TaskBoardItemView> items = currentItems(agent, ctx);
        if (items.isEmpty()) {
            return;
        }
        int revision = items.size();
        String progress = ReactTaskBoardService.progressSummary(items);
        com.sunshine.orchestrator.agent.StepEventBridge.emit(bridgeId, session ->
                timelineSupport.applyUpdate(session, items, revision, progress));
    }

    /** 当前 tasksContext 的 TaskBoardItemView 列表（subject→content，state.wire→status） */
    public static List<TaskBoardItemView> currentItems(Agent agent, RuntimeContext ctx) {
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        if (state == null || state.getTasksContext() == null) {
            return List.of();
        }
        return toItems(state.getTasksContext().getTasks());
    }

    /** 原生 Task 列表 → timeline TaskBoardItemView 列表（subject→content，state.wire→status） */
    public static List<TaskBoardItemView> toItems(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<TaskBoardItemView> items = new ArrayList<>(tasks.size());
        for (Task t : tasks) {
            String status = t.getState() != null ? t.getState().getWire() : "pending";
            items.add(new TaskBoardItemView(t.getId(), t.getSubject(), status));
        }
        return items;
    }
}
