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
        nb.getTaskQueue().add(TaskItem.initial("t1", "调研", List.of(), null, null, null).withStatus("done", null));
        nb.getTaskQueue().add(TaskItem.initial("t2", "分析", List.of("t1"), null, null, null));
        nb.getTaskQueue().add(TaskItem.initial("t3", "废", List.of(), null, null, null).withStatus("obsolete", null));
        List<TaskBoardItemView> views = HarnessTaskBoardProjector.project(nb);
        assertThat(views).extracting(TaskBoardItemView::id, TaskBoardItemView::status)
                .containsExactly(
                        tuple("t1-1", "completed"),
                        tuple("t2-1", "pending"),
                        tuple("t3-1", "cancelled"));
        assertThat(views.get(1).dependsOn()).containsExactly("t1");
    }

    @Test
    void versionedId_appendsRetryIndexForFirstAttemptAndKeepsSuffixed() {
        PlanNotebook nb = PlanNotebook.create("g", "q", "chat", 12, 24);
        nb.getTaskQueue().add(new TaskItem(
                "t1", "调研", "cancelled", List.of(), null, null, null,
                "t1", 1, null, null));
        nb.getTaskQueue().add(new TaskItem(
                "t1-2", "调研", "in_progress", List.of(), null, null, null,
                "t1", 2, "t1", null));
        nb.getTaskQueue().add(new TaskItem(
                "research-arch", "架构", "pending", List.of(), null, null, null,
                "research-arch", 1, null, null));
        List<TaskBoardItemView> views = HarnessTaskBoardProjector.project(nb);
        assertThat(views).extracting(TaskBoardItemView::id, TaskBoardItemView::status)
                .containsExactly(
                        tuple("t1-1", "cancelled"),
                        tuple("t1-2", "in_progress"),
                        tuple("research-arch-1", "pending"));
    }
}
