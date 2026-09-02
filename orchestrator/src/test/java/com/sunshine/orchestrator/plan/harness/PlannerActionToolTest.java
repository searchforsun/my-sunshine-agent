package com.sunshine.orchestrator.plan.harness;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerActionToolTest {

    private final PlannerActionTool actionTool = new PlannerActionTool();

    @AfterEach
    void tearDown() {
        WorkerDispatchTool.clearAllSessionsForTests();
    }

    @Test
    void registerIntoToolkitRegistersBothActionTools() {
        Toolkit tk = new Toolkit();
        actionTool.registerIntoPlannerToolkit(tk);
        assertThat(tk.getToolNames()).contains("plan_submit", "self_assess", "task_status");
    }

    @Test
    void submitPlan_writesTaskQueue() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        WorkerDispatchTool.DispatchSession session = session(nb);

        String result = actionTool.submitPlan(session, List.of(Map.of(
                "taskId", "t1",
                "label", "摸底",
                "dependsOn", List.of(),
                "constraints", "只读",
                "expectedOutput", "摘要",
                "successCriteria", "有结论")));

        assertThat(result).contains("\"ok\":true").contains("\"scheduled\":1");
        assertThat(nb.getTaskQueue()).hasSize(1);
        TaskItem task = nb.getTaskQueue().peek();
        assertThat(task.taskId()).isEqualTo("t1");
        assertThat(task.status()).isEqualTo("pending");
        assertThat(task.constraints()).isEqualTo("只读");
    }

    @Test
    void submitPlan_keepsCancelledHistoryOfSameTaskId() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "摸底", "cancelled", List.of(), null, null, null,
                "t1", 1, null, null));
        WorkerDispatchTool.DispatchSession session = session(nb);

        // Planner 续跑重新 plan_submit 同名 t1（pending）——不得覆盖取消历史，
        // 保证 dispatch_worker(t1) 重派能命中取消记录自动版本化 t1-2
        String result = actionTool.submitPlan(session, List.of(Map.of(
                "taskId", "t1", "label", "摸底", "dependsOn", List.of())));

        assertThat(result).contains("\"ok\":true");
        assertThat(nb.getTaskQueue()).hasSize(1);
        TaskItem task = nb.getTaskQueue().peek();
        assertThat(task.status()).isEqualTo("cancelled");
        assertThat(task.retryIndex()).isEqualTo(1);
        assertThat(task.failReason()).isNull();
    }

    @Test
    void submitPlan_keepsFailedHistoryOfSameTaskId() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "摸底", "fail", List.of(), null, null, null,
                "t1", 1, null, "timeout"));
        WorkerDispatchTool.DispatchSession session = session(nb);

        actionTool.submitPlan(session, List.of(Map.of(
                "taskId", "t1", "label", "摸底", "dependsOn", List.of())));

        assertThat(nb.getTaskQueue()).hasSize(1);
        TaskItem task = nb.getTaskQueue().peek();
        assertThat(task.status()).isEqualTo("fail");
        assertThat(task.failReason()).isEqualTo("timeout");
    }

    @Test
    void submitPlan_rejectsMalformedTasks() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        WorkerDispatchTool.DispatchSession session = session(nb);

        String result = actionTool.submitPlan(session, "not-a-list");

        assertThat(result).contains("\"ok\":false").contains("tasks");
        assertThat(nb.getTaskQueue()).isEmpty();
    }

    @Test
    void submitPlan_rejectsTaskMissingLabel() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        WorkerDispatchTool.DispatchSession session = session(nb);

        String result = actionTool.submitPlan(session, List.of(Map.of("taskId", "t1")));

        assertThat(result).contains("\"ok\":false");
    }

    @Test
    void submitAssess_writesCompletionAndDirection() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        WorkerDispatchTool.DispatchSession session = session(nb);

        String result = actionTool.submitAssess(session, 0.6, "answer", "信息已足");

        assertThat(result).contains("\"ok\":true");
        assertThat(nb.getGoalCompletion()).isEqualTo(0.6);
        assertThat(nb.getNextDirection()).isEqualTo("answer");
    }

    @Test
    void submitAssess_clampsCompletionAndRejectsNonNumber() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        WorkerDispatchTool.DispatchSession session = session(nb);

        actionTool.submitAssess(session, 1.5, "continue", "超限");
        assertThat(nb.getGoalCompletion()).isEqualTo(1.0);

        String bad = actionTool.submitAssess(session, "high", "continue", "非法");
        assertThat(bad).contains("\"ok\":false").contains("goalCompletion");
    }

    @Test
    void submitPlan_withoutSessionReturnsError() {
        String result = actionTool.submitPlan(null, List.of());
        assertThat(result).contains("未绑定");
    }

    @Test
    void submitTaskStatus_returnsQueueStateMetadata() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1-1", "调研架构", "fail", List.of(), "", "", "",
                "t1", 1, null, "timeout"));
        nb.getTaskQueue().add(new TaskItem("t1-2", "调研架构", "in_progress", List.of(), "", "", "",
                "t1", 2, "t1-1", null));
        nb.getTaskQueue().add(new TaskItem("t2", "调研依赖", "pending", List.of(), "", "", "",
                "t2", 1, null, null));
        WorkerDispatchTool.DispatchSession s = session(nb);

        String result = actionTool.submitTaskStatus(s);

        assertThat(result).contains("\"ok\":true");
        assertThat(result).contains("\"taskId\":\"t1-1\"", "\"status\":\"fail\"", "\"failReason\":\"timeout\"");
        assertThat(result).contains("\"taskId\":\"t1-2\"", "\"status\":\"in_progress\"", "\"retryIndex\":2");
        assertThat(result).contains("\"taskId\":\"t2\"", "\"status\":\"pending\"", "\"dependsOn\":[]");
        // 重试版本保留历史：父执行与重派版本都在队列中
        assertThat(result.indexOf("t1-1")).isLessThan(result.indexOf("t1-2"));
    }

    @Test
    void submitTaskStatus_withoutSessionReturnsError() {
        String result = actionTool.submitTaskStatus(null);
        assertThat(result).contains("未绑定");
    }

    private static WorkerDispatchTool.DispatchSession session(PlanNotebook nb) {
        WorkerDispatchTool.DispatchSession s = new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u1", "default", "msg-action", "conv-1", "run-1", 0, "task");
        WorkerDispatchTool.bindSession(s);
        return s;
    }
}
