package com.sunshine.orchestrator.plan.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PlanNotebookTest {
    @Test
    void newNotebookHasNoDecompositionFieldsAndDefaultBudgets() throws Exception {
        PlanNotebook nb = PlanNotebook.create("goal", "query", "task", 12, 24);
        assertThat(nb.getMaxRounds()).isEqualTo(12);
        assertThat(nb.getMaxTotalTasks()).isEqualTo(24);
        assertThat(nb.getTaskQueue()).isEmpty();
        String json = new ObjectMapper().writeValueAsString(nb);
        assertThat(json).doesNotContain("taskDecomposition", "phases", "completeness");
    }

    @Test
    void renderForPlannerKeepsNearRounds() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "chat", 12, 24);
        for (int i = 0; i < 12; i++) {
            nb.appendRound(new RoundRecord(i, task("t" + i), List.of(), 0.1, "ok"));
        }
        String text = nb.renderForPlanner(10);
        assertThat(text).contains("t11").contains("t2");
        assertThat(text).doesNotContain("t0");
    }

    @Test
    void jsonRoundTripPreservesFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PlanNotebook nb = PlanNotebook.create("goal", "query", "task", 12, 24);
        nb.setSessionId("sess-1");
        nb.setGoalCompletion(0.5);
        nb.getTaskQueue().add(new TaskItem("t1", "label", "pending", List.of(), null, null, null));
        PlanNotebook restored = mapper.readValue(mapper.writeValueAsString(nb), PlanNotebook.class);
        assertThat(restored.getOriginalGoal()).isEqualTo("goal");
        assertThat(restored.getSessionId()).isEqualTo("sess-1");
        assertThat(restored.getTaskQueue()).hasSize(1);
        assertThat(restored.getCreatedAt()).isEqualTo(nb.getCreatedAt());
    }

    private static TaskItem task(String taskId) {
        return new TaskItem(taskId, taskId, "done", List.of(), null, null, null);
    }
}
