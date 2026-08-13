package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class HarnessTaskBoardProjectorTest {
    @Test
    void mapsDoneAndFailAndDependsOn() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "chat", 12, 24);
        nb.getTaskQueue().add(new TaskItem("t1", "调研", "done", List.of(), null, null, null));
        nb.getTaskQueue().add(new TaskItem("t2", "分析", "pending", List.of("t1"), null, null, null));
        nb.getTaskQueue().add(new TaskItem("t3", "废", "obsolete", List.of(), null, null, null));
        List<TaskBoardItemView> views = HarnessTaskBoardProjector.project(nb);
        assertThat(views).extracting(TaskBoardItemView::id, TaskBoardItemView::status)
                .containsExactly(
                        tuple("t1", "completed"),
                        tuple("t2", "pending"),
                        tuple("t3", "cancelled"));
        assertThat(views.get(1).dependsOn()).containsExactly("t1");
    }
}
