package com.sunshine.orchestrator.taskboard;

import io.agentscope.core.state.Task;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 原生 Task 列表 → timeline TaskBoardItemView 映射 */
class TodoTasksBridgeTest {

    @Test
    void toItems_mapsSubjectStateAndId() {
        List<Task> tasks = List.of(
                Task.builder().id("t1").subject("分析需求").description("分析需求")
                        .state(Task.State.IN_PROGRESS).build(),
                Task.builder().id("t2").subject("撰写报告").description("撰写报告")
                        .state(Task.State.PENDING).build(),
                Task.builder().id("t3").subject("校对").description("校对")
                        .state(Task.State.COMPLETED).build());

        List<TaskBoardItemView> items = TodoTasksBridge.toItems(tasks);

        assertThat(items).hasSize(3);
        assertThat(items.get(0).id()).isEqualTo("t1");
        assertThat(items.get(0).content()).isEqualTo("分析需求");
        assertThat(items.get(0).status()).isEqualTo("in_progress");
        assertThat(items.get(1).status()).isEqualTo("pending");
        assertThat(items.get(2).status()).isEqualTo("completed");
    }

    @Test
    void toItems_emptyOrNull_returnsEmpty() {
        assertThat(TodoTasksBridge.toItems(null)).isEmpty();
        assertThat(TodoTasksBridge.toItems(List.of())).isEmpty();
    }

    @Test
    void toItems_nullState_defaultsPending() {
        List<Task> tasks = List.of(
                Task.builder().id("t1").subject("任务").description("任务").state(null).build());

        List<TaskBoardItemView> items = TodoTasksBridge.toItems(tasks);

        assertThat(items.get(0).status()).isEqualTo("pending");
    }
}
