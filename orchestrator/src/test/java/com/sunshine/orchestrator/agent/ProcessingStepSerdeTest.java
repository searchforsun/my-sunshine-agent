package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProcessingStepSerdeTest {

    @Test
    void metadataToMap_includesTaskBoardFields() {
        StepMetadata metadata = StepMetadata.withTasks(
                List.of(
                        new TaskBoardItemView("t1", "检索制度", "completed"),
                        new TaskBoardItemView("t2", "查询待审批", "in_progress")),
                2,
                "1/2 已完成");
        Map<String, Object> map = ProcessingStepSerde.metadataToMap(metadata);

        assertThat(map.get("taskRevision")).isEqualTo(2);
        assertThat(map.get("taskProgress")).isEqualTo("1/2 已完成");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) map.get("tasks");
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(1).get("status")).isEqualTo("in_progress");
    }

    @Test
    void metaStep_serializesTasksStepForSse() {
        ProcessingStep step = new ProcessingStep(
                "tasks",
                "tasks",
                "running",
                new StepSummary("规划任务步骤", "正在执行：查询待审批", null),
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                1L,
                "任务清单",
                StepMetadata.withTasks(
                        List.of(new TaskBoardItemView("t1", "检索制度", "completed")),
                        1,
                        "1/1 已完成"),
                null,
                null);
        String json = new com.sunshine.orchestrator.conversation.GenerationFlushScheduler(mock(), mock())
                .metaStep(step);

        assertThat(json).contains("\"phase\":\"tasks\"");
        assertThat(json).contains("\"tasks\"");
        assertThat(json).contains("检索制度");
    }
}
