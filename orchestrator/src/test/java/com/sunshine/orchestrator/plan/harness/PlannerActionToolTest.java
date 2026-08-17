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
        assertThat(tk.getToolNames()).contains("plan_submit", "self_assess");
    }

    @Test
    void submitPlan_writesTaskQueueAndSetsSignal() {
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
        assertThat(session.signals().planReceived().get()).isTrue();
        assertThat(nb.getTaskQueue()).hasSize(1);
        TaskItem task = nb.getTaskQueue().peek();
        assertThat(task.taskId()).isEqualTo("t1");
        assertThat(task.status()).isEqualTo("pending");
        assertThat(task.constraints()).isEqualTo("只读");
    }

    @Test
    void submitPlan_rejectsMalformedTasks() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        WorkerDispatchTool.DispatchSession session = session(nb);

        String result = actionTool.submitPlan(session, "not-a-list");

        assertThat(result).contains("\"ok\":false").contains("tasks");
        assertThat(session.signals().planReceived().get()).isFalse();
        assertThat(nb.getTaskQueue()).isEmpty();
    }

    @Test
    void submitPlan_rejectsTaskMissingLabel() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        WorkerDispatchTool.DispatchSession session = session(nb);

        String result = actionTool.submitPlan(session, List.of(Map.of("taskId", "t1")));

        assertThat(result).contains("\"ok\":false");
        assertThat(session.signals().planReceived().get()).isFalse();
    }

    @Test
    void submitAssess_writesCompletionAndDirectionAndSetsSignal() {
        PlanNotebook nb = PlanNotebook.create("goal", "q", "task", 12, 24);
        WorkerDispatchTool.DispatchSession session = session(nb);

        String result = actionTool.submitAssess(session, 0.6, "answer", "信息已足");

        assertThat(result).contains("\"ok\":true");
        assertThat(session.signals().assessReceived().get()).isTrue();
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

    private static WorkerDispatchTool.DispatchSession session(PlanNotebook nb) {
        WorkerDispatchTool.DispatchSession s = new WorkerDispatchTool.DispatchSession(
                nb, List.of(), "u1", "default", "msg-action", "conv-1", "run-1", 0, "task",
                WorkerDispatchTool.DispatchSession.ActionSignals.fresh());
        WorkerDispatchTool.bindSession(s);
        return s;
    }
}
