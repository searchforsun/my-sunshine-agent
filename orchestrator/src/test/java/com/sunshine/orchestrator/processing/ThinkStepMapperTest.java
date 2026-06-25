package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.StepSummary;
import com.sunshine.orchestrator.routing.ExecutionMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ThinkStepMapperTest {

    @Test
    void reasoningOpensThinkStepBeforeGenerate() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(doneStep("intent")));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "写一段 Python 快速排序");

        List<StreamToken> mapped = mapper.map(StreamToken.reasoning("先分析需求"));

        assertThat(mapped).hasSize(2);
        assertThat(mapped.get(0).isStep()).isTrue();
        assertThat(mapped.get(0).step().id()).isEqualTo("think");
        assertThat(mapped.get(1).isStepDelta()).isTrue();
        assertThat(mapped.get(1).stepId()).isEqualTo("think");
        assertThat(mapped.get(1).text()).isEqualTo("先分析需求");
    }

    @Test
    void contentCompletesThinkAndOpensGenerate() {
        List<ProcessingStep> steps = new ArrayList<>();
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "hello");

        mapper.map(StreamToken.reasoning("思考"));
        List<StreamToken> mapped = mapper.map(StreamToken.content("回答"));

        assertThat(mapped.stream().anyMatch(t -> t.isStep() && "done".equals(t.step().lifecycle())
                && "think".equals(t.step().id()))).isTrue();
        assertThat(mapped.stream().anyMatch(t -> t.isStep() && "running".equals(t.step().lifecycle())
                && "generate".equals(t.step().id()))).isTrue();
        assertThat(mapped.get(mapped.size() - 1).isContent()).isTrue();
    }

    @Test
    void reasoningAlwaysUsesThinkStep() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(runningStep("tool-list_finance_messages")));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "考勤制度是什么");

        List<StreamToken> mapped = mapper.map(StreamToken.reasoning("检索结果分析"));

        assertThat(mapped.stream().anyMatch(t -> t.isStep() && "think".equals(t.step().id()))).isTrue();
        assertThat(mapped.stream().anyMatch(t -> t.isStepDelta()
                && "think".equals(t.stepId())
                && "检索结果分析".equals(t.text()))).isTrue();
    }

    @Test
    void contentOnlySkipsThinkStep() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(doneStep("intent")));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "hello");

        List<StreamToken> mapped = mapper.map(StreamToken.content("直接回答"));

        assertThat(steps.stream().noneMatch(s -> "think".equals(s.id()))).isTrue();
        assertThat(mapped.stream().anyMatch(t -> t.isStep() && "generate".equals(t.step().id()))).isTrue();
    }

    @Test
    void workflowContentDoesNotOpenGenerate() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(
                doneStep("intent"),
                doneStep("plan"),
                runningStep("node-finance-list")));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "有哪些待审批报销");

        List<StreamToken> mapped = mapper.map(StreamToken.content("回答正文"));

        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).isContent()).isTrue();
        assertThat(steps.stream().noneMatch(s -> "generate".equals(s.id()))).isTrue();
        assertThat(mapper.finish().stream().noneMatch(t -> t.isStep()
                && "generate".equals(t.step().id()))).isTrue();
    }

    @Test
    void contentOpensGenerateAndFinishCompletesIt() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(runningStep("think")));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "有哪些待审批报销");

        List<StreamToken> mapped = mapper.map(StreamToken.content("回答正文"));

        assertThat(mapped.stream().anyMatch(t -> t.isStep() && "generate".equals(t.step().id()))).isTrue();
        assertThat(mapper.finish().stream().anyMatch(t -> t.isStep()
                && "generate".equals(t.step().id())
                && "done".equals(t.step().lifecycle()))).isTrue();
    }

    @Test
    void finishOnStreamFailure_doesNotOpenEmptyGenerate() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(doneStep("intent")));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "有哪些待审批报销");

        assertThat(mapper.finish(true).stream().noneMatch(t -> t.isStep()
                && "generate".equals(t.step().id()))).isTrue();
    }

    @Test
    void reasoningAfterCompletedThinkOpensNewIteration() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(
                doneStep("think"),
                doneStep("rag")));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "报销相关");

        List<StreamToken> mapped = mapper.map(StreamToken.reasoning("结合检索结果分析"));

        assertThat(mapped.stream().anyMatch(t -> t.isStep() && "think-2".equals(t.step().id()))).isTrue();
        assertThat(mapped.stream().anyMatch(t -> t.isStepDelta()
                && "think-2".equals(t.stepId())
                && "结合检索结果分析".equals(t.text()))).isTrue();
    }

    @Test
    void simpleLlmMode_usesComposeWordingNotToolPlanning() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(doneStep("intent")));
        AtomicReference<ExecutionMode> mode = new AtomicReference<>(ExecutionMode.SIMPLE_LLM);
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "个人所得税专项附加扣除怎么填", mode);

        applyMapped(mapper, steps, StreamToken.reasoning("构思填报步骤"));
        applyMapped(mapper, steps, StreamToken.content("回答正文"));
        mapper.finish().stream().filter(StreamToken::isStep)
                .forEach(t -> ProcessingStepMerger.upsert(steps, t.step()));

        ProcessingStep think = steps.stream().filter(s -> "think".equals(s.id())).findFirst().orElseThrow();
        assertThat(think.label()).isEqualTo("构思回答");
        assertThat(think.summary().active()).contains("构思").doesNotContain("工具");
        assertThat(think.summary().after()).contains("作答构思").doesNotContain("工具");
    }

    private static void applyMapped(ThinkStepMapper mapper, List<ProcessingStep> steps, StreamToken token) {
        mapper.map(token).stream().filter(StreamToken::isStep)
                .forEach(t -> ProcessingStepMerger.upsert(steps, t.step()));
    }

    @Test
    void stepDeltaWithNodeId_passesThroughToWorkflowNode() {
        List<ProcessingStep> steps = new ArrayList<>();
        steps.add(runningStep("node-n4"));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "test");

        List<StreamToken> mapped = mapper.map(StreamToken.stepDelta("node-n4", "reasoning", "分析合规点"));

        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).stepId()).isEqualTo("node-n4");
        assertThat(mapped.get(0).channel()).isEqualTo("reasoning");
    }

    @Test
    void stepDeltaWithThink2Id_passesThrough() {
        List<ProcessingStep> steps = new ArrayList<>();
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "test");

        mapper.map(StreamToken.stepDelta("think-2", "reasoning", "第二轮思考"));

        assertThat(steps.stream().anyMatch(s -> "think-2".equals(s.id()))).isTrue();
    }

    private static ProcessingStep runningStep(String id) {
        return new ProcessingStep(
                id, id, "running",
                new StepSummary("before", "active", null),
                1L, null, null, null, null, null, null,
                System.currentTimeMillis(), "running", id, null, null);
    }

    private static ProcessingStep doneStep(String id) {
        return new ProcessingStep(
                id, id, "done",
                new StepSummary("before", "active", "after"),
                1L, 2L, 1L, null, null, null, null,
                System.currentTimeMillis(), "done", id, null, null);
    }
}
