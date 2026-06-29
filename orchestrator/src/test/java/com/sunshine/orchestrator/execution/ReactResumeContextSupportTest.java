package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.processing.HitlStepMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReactResumeContextSupportTest {

    @Test
    void buildInjectedBlocks_emptySteps() {
        assertThat(ReactResumeContextSupport.buildInjectedBlocks(null)).isEmpty();
        assertThat(ReactResumeContextSupport.buildInjectedBlocks(List.of())).isEmpty();
    }

    @Test
    void buildInjectedBlocks_skipsIntentAndIncludesThinkAndTool() {
        List<ProcessingStep> steps = List.of(
                intentStep(),
                thinkStep("think", "先查 OA 再审批"),
                toolStep("tool-search_knowledge@100", "制度：报销需总监审批"),
                awaitingHitlToolStep());

        List<String> blocks = ReactResumeContextSupport.buildInjectedBlocks(steps);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).contains("先查 OA 再审批");
        assertThat(blocks.get(1)).contains("search_knowledge").contains("报销需总监审批");
        assertThat(blocks.get(2)).contains("待确认写操作").contains("taskId=T1001");
        assertThat(blocks.stream().noneMatch(b -> b.contains("判定为"))).isTrue();
    }

    @Test
    void buildInjectedBlocks_excludesAwaitingHitlToolResult() {
        ProcessingStep awaiting = awaitingHitlToolStep();
        List<String> blocks = ReactResumeContextSupport.buildInjectedBlocks(List.of(awaiting));

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(block).contains("待确认写操作");
            assertThat(block).doesNotContain("已执行");
        });
    }

    private static ProcessingStep intentStep() {
        return new ProcessingStep(
                "intent",
                "intent",
                "done",
                new StepSummary(null, null, "判定为 ReAct"),
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
                null);
    }

    private static ProcessingStep thinkStep(String id, String reasoning) {
        return new ProcessingStep(
                id,
                "think",
                "done",
                new StepSummary(null, null, reasoning),
                1L,
                2L,
                1L,
                null,
                reasoning,
                null,
                null,
                2L,
                "规划推理",
                null,
                null,
                null);
    }

    private static ProcessingStep toolStep(String id, String result) {
        return new ProcessingStep(
                id,
                "tool",
                "done",
                new StepSummary(null, null, "检索完成"),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                result,
                2L,
                "知识检索",
                null,
                null,
                null);
    }

    private static ProcessingStep awaitingHitlToolStep() {
        HitlStepMeta hitl = HitlStepMeta.awaiting(
                "token", "审批 OA 待办", "taskId=T1001", System.currentTimeMillis() + 60_000);
        StepMetadata meta = StepMetadata.withHitl(null, hitl);
        return new ProcessingStep(
                "tool-approve_oa_task@200",
                "tool",
                "paused",
                new StepSummary(null, "已暂停", "已暂停"),
                1L,
                2L,
                1L,
                null,
                null,
                null,
                null,
                2L,
                "审批 OA 待办",
                meta,
                null,
                null);
    }
}
