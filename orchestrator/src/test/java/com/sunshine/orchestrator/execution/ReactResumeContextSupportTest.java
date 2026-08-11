package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.DecisionAnswer;
import com.sunshine.orchestrator.agent.DecisionOption;
import com.sunshine.orchestrator.agent.DecisionQuestion;
import com.sunshine.orchestrator.agent.DecisionResult;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.processing.DecisionStepMeta;
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

    @Test
    void buildInjectedBlocks_includesAwaitingDecision() {
        List<String> blocks = ReactResumeContextSupport.buildInjectedBlocks(List.of(awaitingDecisionStep()));

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(block).startsWith("【待决策】");
            assertThat(block).contains("选哪个方案？");
            assertThat(block).contains("plan_a: 方案A");
            assertThat(block).contains("plan_b: 方案B很长很长的描述内容用于确认不被截断");
        });
    }

    @Test
    void buildInjectedBlocks_reactRestartExcludesAwaitingDecision() {
        List<String> blocks = ReactResumeContextSupport.buildInjectedBlocks(
                List.of(awaitingDecisionStep()), false);
        assertThat(blocks).isEmpty();
    }

    @Test
    void buildResolvedDecisionBlock_containsShortFormatAnswers() {
        DecisionResult result = new DecisionResult(
                "answered",
                "选哪个方案？",
                List.of(new DecisionAnswer("q1", List.of("plan_a"), "备注原文不截断")),
                1L);
        String block = ReactResumeContextSupport.buildResolvedDecisionBlock(result);

        assertThat(block).contains("【用户决策】");
        assertThat(block).contains("选哪个方案？");
        assertThat(block).contains("outcome=answered");
        assertThat(block).contains("q.q1=plan_a");
        assertThat(block).contains("q.q1.custom=备注原文不截断");
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
                null,
                null);
    }

    private static ProcessingStep awaitingHitlToolStep() {
        HitlStepMeta hitl = HitlStepMeta.awaiting(
                "token", "审批 OA 待办", "taskId=T1001", System.currentTimeMillis() + 60_000);
        StepMetadata meta = StepMetadata.withHitl(null, hitl);
        return new ProcessingStep(
                "tool-sdk__sunshine-oa__approve_oa_task@200",
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
                null,
                null);
    }

    private static ProcessingStep awaitingDecisionStep() {
        String longLabel = "方案B很长很长的描述内容用于确认不被截断";
        DecisionStepMeta decision = new DecisionStepMeta(
                "tok-d1",
                "选哪个方案？",
                List.of(new DecisionQuestion(
                        "q1",
                        "选哪个方案？",
                        List.of(
                                new DecisionOption("plan_a", "方案A"),
                                new DecisionOption("plan_b", longLabel)),
                        false)),
                System.currentTimeMillis() + 60_000,
                null,
                null);
        return new ProcessingStep(
                "decision-tok-d1",
                "decision",
                "awaiting",
                new StepSummary(null, "等待决策", null),
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                2L,
                "选哪个方案？",
                StepMetadata.withDecision(null, decision),
                null,
                null,
                null);
    }
}
