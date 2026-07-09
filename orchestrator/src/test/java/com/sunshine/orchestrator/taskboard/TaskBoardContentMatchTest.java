package com.sunshine.orchestrator.taskboard;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskBoardContentMatchTest {

    @Test
    void findMatchingId_matchesGenericApprovalToConcreteId() {
        Map<String, TaskBoardItemView> map = new LinkedHashMap<>();
        map.put("t1", new TaskBoardItemView("t1", "提交采购付款审批", "pending"));
        map.put("t2", new TaskBoardItemView("t2", "提交预算调整审批", "pending"));

        String matched = TaskBoardContentMatch.findMatchingId(
                map, new TaskBoardItemView(null, "提交 1002 采购付款审批", "completed"));

        assertThat(matched).isEqualTo("t1");
    }

    @Test
    void dedupeBySemanticKey_mergesGenericAndConcrete() {
        List<TaskBoardItemView> items = List.of(
                new TaskBoardItemView("t1", "提交采购付款审批", "pending"),
                new TaskBoardItemView("t2", "提交 1002 采购付款审批", "completed"),
                new TaskBoardItemView("t3", "查询待审批财务消息", "completed"));

        List<TaskBoardItemView> deduped = TaskBoardContentMatch.dedupeBySemanticKey(items);

        assertThat(deduped).hasSize(2);
        assertThat(deduped.stream().anyMatch(i -> i.content().contains("1002"))).isTrue();
        assertThat(deduped.stream().filter(i -> i.content().contains("采购")).count()).isEqualTo(1);
    }
}
