package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    void reasoningRoutesToAgentWhenAgentRunning() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(runningStep("agent")));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "考勤制度是什么");

        List<StreamToken> mapped = mapper.map(StreamToken.reasoning("检索结果分析"));

        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).stepId()).isEqualTo("agent");
        assertThat(mapped.stream().noneMatch(t -> t.isStep() && "think".equals(t.step().id()))).isTrue();
    }

    @Test
    void contentOnlySkipsThinkStep() {
        List<ProcessingStep> steps = new ArrayList<>(List.of(doneStep("intent")));
        ThinkStepMapper mapper = new ThinkStepMapper(steps, "hello");

        List<StreamToken> mapped = mapper.map(StreamToken.content("直接回答"));

        assertThat(steps.stream().noneMatch(s -> "think".equals(s.id()))).isTrue();
        assertThat(mapped.stream().anyMatch(t -> t.isStep() && "generate".equals(t.step().id()))).isTrue();
    }

    private static ProcessingStep runningStep(String id) {
        return new ProcessingStep(
                id, id, "running",
                new StepSummary("before", "active", null),
                1L, null, null, null, null, null, null,
                System.currentTimeMillis(), "running", id);
    }

    private static ProcessingStep doneStep(String id) {
        return new ProcessingStep(
                id, id, "done",
                new StepSummary("before", "active", "after"),
                1L, 2L, 1L, null, null, null, null,
                System.currentTimeMillis(), "done", id);
    }
}
