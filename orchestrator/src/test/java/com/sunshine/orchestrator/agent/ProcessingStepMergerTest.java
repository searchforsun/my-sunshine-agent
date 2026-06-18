package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.StepSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessingStepMergerTest {

    @Test
    @DisplayName("currentPhaseSummary：done 步骤只保留 after")
    void currentPhaseSummary_doneOnlyAfter() {
        ProcessingStep step = new ProcessingStep(
                "intent",
                "intent",
                "done",
                new StepSummary("阅读问题", "正在分析", "判定为简单对话"),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                null,
                2L,
                "done",
                "识别意图",
                null
        );

        StepSummary phase = ProcessingStepMerger.currentPhaseSummary(step);

        assertThat(phase.before()).isNull();
        assertThat(phase.active()).isNull();
        assertThat(phase.after()).isEqualTo("判定为简单对话");
    }

    @Test
    @DisplayName("currentPhaseSummary：running 步骤只保留 active")
    void currentPhaseSummary_runningOnlyActive() {
        ProcessingStep step = new ProcessingStep(
                "think-2",
                "think",
                "running",
                new StepSummary("分析逻辑", "正在推演", null),
                1L,
                null,
                null,
                null,
                "推理片段",
                null,
                null,
                1L,
                "running",
                "思考过程",
                null
        );

        StepSummary phase = ProcessingStepMerger.currentPhaseSummary(step);

        assertThat(phase.before()).isNull();
        assertThat(phase.active()).isEqualTo("正在推演");
        assertThat(phase.after()).isNull();
    }

    @Test
    @DisplayName("currentPhaseSummary：done 且 after 为空时不回退 active")
    void currentPhaseSummary_doneNullAfterNoActiveFallback() {
        ProcessingStep step = new ProcessingStep(
                "intent",
                "intent",
                "done",
                new StepSummary("阅读问题", "正在分析", null),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                null,
                2L,
                "done",
                "识别意图",
                null
        );

        StepSummary phase = ProcessingStepMerger.currentPhaseSummary(step);

        assertThat(phase).isNull();
    }

    @Test
    void toPersistJson_omitsEmptyAndSinglePhaseSummary() {
        ProcessingStep done = new ProcessingStep(
                "intent",
                "intent",
                "done",
                new StepSummary("before", "active", "after"),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                null,
                2L,
                "done",
                "识别意图",
                null
        );
        ProcessingStep think = new ProcessingStep(
                "think",
                "think",
                "done",
                new StepSummary("b", "a", "思考完成"),
                3L,
                4L,
                1L,
                null,
                "完整推理",
                null,
                null,
                4L,
                "done",
                "思考过程",
                null
        );

        String json = ProcessingStepMerger.toPersistJson(List.of(done, think));

        assertThat(json).contains("\"after\":\"after\"");
        assertThat(json).doesNotContain("\"before\":\"before\"");
        assertThat(json).doesNotContain("\"active\":\"active\"");
        assertThat(json).contains("\"reasoning\":\"完整推理\"");
        assertThat(json).doesNotContain("\"detail\"");
        assertThat(json).doesNotContain("\"output\"");
    }

    @Test
    @DisplayName("appendReasoning：真增量直接拼接")
    void appendReasoning_concatenatesIncrements() {
        assertThat(ProcessingStepMerger.appendReasoning("第一步", "完成。")).isEqualTo("第一步完成。");
    }
}
