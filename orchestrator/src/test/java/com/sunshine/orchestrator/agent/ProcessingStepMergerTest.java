package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.processing.TimelineLabelJUnitExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(TimelineLabelJUnitExtension.class)
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
                "识别意图",
                null,
                null,
                null,
                null
        );

        StepSummary phase = ProcessingStepSerde.currentPhaseSummary(step);

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
                "思考过程",
                null,
                null,
                null,
                null
        );

        StepSummary phase = ProcessingStepSerde.currentPhaseSummary(step);

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
                "识别意图",
                null,
                null,
                null,
                null
        );

        StepSummary phase = ProcessingStepSerde.currentPhaseSummary(step);

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
                "识别意图",
                null,
                null,
                null,
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
                "思考过程",
                null,
                null,
                null,
                null
        );

        String json = ProcessingStepSerde.toPersistJson(List.of(done, think));

        assertThat(json).contains("\"after\":\"after\"");
        assertThat(json).doesNotContain("\"before\":\"before\"");
        assertThat(json).doesNotContain("\"active\":\"active\"");
        assertThat(json).contains("\"reasoning\":\"完整推理\"");
        assertThat(json).doesNotContain("\"detail\"");
        assertThat(json).doesNotContain("\"output\"");
    }

    @Test
    @DisplayName("toPersistJson：仅 routingReason 的 intent metadata 仍落库")
    void toPersistJson_persistsRoutingReasonOnlyMetadata() {
        com.sunshine.orchestrator.processing.StepMetadata metadata =
                com.sunshine.orchestrator.processing.StepMetadata.fromRouting(
                        new com.sunshine.orchestrator.routing.ExecutionPlan(
                                com.sunshine.orchestrator.routing.ExecutionMode.FAST,
                                null,
                                java.util.Map.of(),
                                "user:forced-fast"));
        ProcessingStep intent = new ProcessingStep(
                "intent",
                "intent",
                "done",
                new StepSummary(null, null, "将自主推理处理"),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                null,
                2L,
                "识别意图",
                metadata,
                null,
                null,
                null
        );
        String json = ProcessingStepSerde.toPersistJson(List.of(intent));
        assertThat(json).contains("\"routingReason\":\"user:forced-fast\"");
    }

    @Test
    @DisplayName("applyDelta result 保留仅含换行/空格的 token")
    void applyDelta_resultPreservesWhitespaceOnlyChunks() {
        List<ProcessingStep> steps = new java.util.ArrayList<>();
        ProcessingStepMerger.applyDelta(steps, "react-policy-s1", "result", "##");
        ProcessingStepMerger.applyDelta(steps, "react-policy-s1", "result", " ");
        ProcessingStepMerger.applyDelta(steps, "react-policy-s1", "result", "一、\n\n");
        ProcessingStepMerger.applyDelta(steps, "react-policy-s1", "result", "正文");
        assertThat(steps.get(0).result()).isEqualTo("## 一、\n\n正文");
    }

    @Test
    @DisplayName("mergeSteps done 态 result 覆盖 delta 累积")
    void mergeSteps_doneResultReplacesStreamedAccumulation() {
        ProcessingStep running = new ProcessingStep(
                "react-x-s1", "react", "running", null,
                1L, null, null, null, null, null, "部分流式",
                1L, "智能体", null, null, null, null);
        ProcessingStep done = new ProcessingStep(
                "react-x-s1", "react", "done", null,
                1L, 2L, 1L, null, null, null, "完整终稿",
                2L, "智能体", null, null, null, null);
        List<ProcessingStep> steps = new java.util.ArrayList<>();
        ProcessingStepMerger.upsert(steps, running);
        ProcessingStepMerger.upsert(steps, done);
        assertThat(steps.get(0).result()).isEqualTo("完整终稿");
    }

    @Test
    @DisplayName("applyDelta result 通道增量拼接并落库")
    void applyDelta_resultChannelConcatenates() {
        java.util.List<ProcessingStep> steps = new java.util.ArrayList<>();
        ProcessingStepMerger.applyDelta(steps, "node-answer", "result", "您好，");
        ProcessingStepMerger.applyDelta(steps, "node-answer", "result", "当前无待办。");
        ProcessingStepMerger.applyDelta(steps, "node-answer", "result", "。");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).result()).isEqualTo("您好，当前无待办。。");
        String json = ProcessingStepSerde.toPersistJson(steps);
        assertThat(json).contains("您好，当前无待办。");
    }

    @Test
    @DisplayName("appendReasoning：真增量直接拼接")
    void appendReasoning_concatenatesIncrements() {
        assertThat(ProcessingStepMerger.appendReasoning("第一步", "完成。")).isEqualTo("第一步完成。");
    }

    @Test
    @DisplayName("applyDelta step_summary 通道写入 stepSummary，不动 output/result")
    void applyDelta_stepSummaryChannelWritesStepSummary() {
        List<ProcessingStep> steps = new java.util.ArrayList<>();
        ProcessingStepMerger.applyDelta(steps, "think-3", "reasoning", "思考过程");
        ProcessingStepMerger.applyDelta(steps, "think-3", "step_summary", "先规划要做的事");
        assertThat(steps).hasSize(1);
        ProcessingStep step = steps.get(0);
        assertThat(step.stepSummary()).isEqualTo("先规划要做的事");
        assertThat(step.reasoning()).isEqualTo("思考过程");
        assertThat(step.output()).isNull();
        String json = ProcessingStepSerde.toPersistJson(steps);
        assertThat(json).contains("\"stepSummary\":\"先规划要做的事\"");
    }

    @Test
    @DisplayName("mergeSteps running 快照：reasoning 前缀合并，禁止全量二次 append")
    void mergeSteps_runningReasoningSnapshot_usesPrefixMerge() {
        List<ProcessingStep> steps = new java.util.ArrayList<>();
        ProcessingStepMerger.upsert(steps, runningWithReasoning("think", "你"));
        ProcessingStepMerger.upsert(steps, runningWithReasoning("think", "你好"));
        ProcessingStepMerger.upsert(steps, runningWithReasoning("think", "你好，世界"));
        assertThat(steps.get(0).reasoning()).isEqualTo("你好，世界");
    }

    private static ProcessingStep runningWithReasoning(String id, String reasoning) {
        long ts = System.currentTimeMillis();
        return new ProcessingStep(
                id, "think", "running", null,
                ts, null, null, null, reasoning, null, null,
                ts, "思考", null, null, null, null);
    }

    @Test
    @DisplayName("retainIntentStepsOnly：仅保留 intent 步")
    void retainIntentStepsOnly_keepsIntentOnly() {
        List<ProcessingStep> steps = List.of(
                intentLike("intent"),
                intentLike("think"),
                intentLike("tool-x@1"));

        List<ProcessingStep> kept = ProcessingStepLifecycleOps.retainIntentStepsOnly(steps);

        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).id()).isEqualTo("intent");
    }

    @Test
    @DisplayName("upsert：paused+after 不被后续 running 覆盖（spawn 取消）")
    void upsert_cancelPausedNotOverwrittenByRunning() {
        ProcessingStep cancelled = new ProcessingStep(
                "subagent-r1",
                "subagent",
                "paused",
                new StepSummary("委派子任务", null, "已取消"),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                "用户已取消子任务",
                2L,
                "子任务",
                null,
                null,
                null,
                null);
        ProcessingStep lateRunning = new ProcessingStep(
                "subagent-r1",
                "subagent",
                "running",
                new StepSummary("委派子任务", "子任务执行中", null),
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                3L,
                "子任务",
                null,
                null,
                null,
                null);
        java.util.ArrayList<ProcessingStep> steps = new java.util.ArrayList<>();
        ProcessingStepMerger.upsert(steps, cancelled);
        ProcessingStepMerger.upsert(steps, lateRunning);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).lifecycle()).isEqualTo("paused");
        assertThat(steps.get(0).summary().after()).isEqualTo("已取消");
    }

    @Test
    @DisplayName("upsert：paused+after 不被后续 done 覆盖")
    void upsert_cancelPausedNotOverwrittenByDone() {
        ProcessingStep cancelled = new ProcessingStep(
                "subagent-r2",
                "subagent",
                "paused",
                new StepSummary("委派子任务", null, "已取消"),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                "用户已取消子任务",
                2L,
                "子任务",
                null,
                null,
                null,
                null);
        ProcessingStep lateDone = new ProcessingStep(
                "subagent-r2",
                "subagent",
                "done",
                new StepSummary("委派子任务", null, "子任务完成"),
                1L,
                3L,
                2L,
                null,
                null,
                null,
                "答案",
                3L,
                "子任务",
                null,
                null,
                null,
                null);
        java.util.ArrayList<ProcessingStep> steps = new java.util.ArrayList<>();
        ProcessingStepMerger.upsert(steps, cancelled);
        ProcessingStepMerger.upsert(steps, lateDone);
        assertThat(steps.get(0).lifecycle()).isEqualTo("paused");
        assertThat(steps.get(0).summary().after()).isEqualTo("已取消");
    }

    private static ProcessingStep intentLike(String id) {
        return new ProcessingStep(
                id,
                "intent".equals(id) ? "intent" : "tool",
                "done",
                new StepSummary(null, null, "x"),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                null,
                2L,
                id,
                null,
                null,
                null,
                null);
    }
}
