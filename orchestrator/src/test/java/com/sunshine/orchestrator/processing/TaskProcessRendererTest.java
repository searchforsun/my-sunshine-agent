package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * task Near 完整过程装载（task-scene §6.6）：steps JSON → think 推理全文 + tool 序列原文，
 * 零 LLM；写/改类保留输出原文（完整 patch/代码），读/执行类 ≤200 + refs。
 */
class TaskProcessRendererTest {

    @Test
    void renderProcessLines_thinkAndTools_keepsFullReasoning() {
        ProcessingStep think = withReasoning(
                ProcessingStep.done("think", "think", "思考", "分析"),
                "先查报销单，再核对金额是否超限。这是完整推理全文，应保留。");
        ProcessingStep exec = ProcessingStep.done(
                        "tool-sandbox__exec@1710000000001", "tool", "执行命令", "exit 0")
                .withMetadata(StepMetadata.withToolSchema(null, "cmd=pytest", 0));
        ProcessingStep edit = ProcessingStep.done(
                        "tool-sandbox__edit@1710000000002", "tool", "修改文件",
                        "--- a/a.py\n+++ b/a.py\n+print(2)")
                .withMetadata(StepMetadata.withToolSchema(null, "path=/workspace/a.py", null));
        String json = ProcessingStepSerde.toJson(List.of(think, exec, edit));

        List<String> lines = TaskProcessRenderer.renderProcessLines(json);

        assertThat(lines).containsExactly(
                "think: 先查报销单，再核对金额是否超限。这是完整推理全文，应保留。",
                "[sandbox__exec] keyArgs=cmd=pytest status=ok exit=0 · result=exit 0",
                "[sandbox__edit] keyArgs=path=/workspace/a.py status=ok"
                        + " · result=--- a/a.py\n+++ b/a.py\n+print(2)");
    }

    @Test
    void renderProcessLines_runningThink_skipped() {
        // think 无 reasoning（半截推理未收口）不入完整过程行
        ProcessingStep think = ProcessingStep.done("think-2", "think", "思考", "分析");
        ProcessingStep tool = ProcessingStep.done(
                        "tool-sandbox__read@1710000000004", "tool", "读取文件", "内容")
                .withMetadata(StepMetadata.withToolSchema(null, "path=/workspace/a.py", null));
        String json = ProcessingStepSerde.toJson(List.of(think, tool));

        List<String> lines = TaskProcessRenderer.renderProcessLines(json);

        assertThat(lines).containsExactly(
                "[sandbox__read] keyArgs=path=/workspace/a.py status=ok · result=内容");
    }

    @Test
    void renderProcessLines_blankJson_returnsEmpty() {
        assertThat(TaskProcessRenderer.renderProcessLines(null)).isEmpty();
        assertThat(TaskProcessRenderer.renderProcessLines("   ")).isEmpty();
        assertThat(TaskProcessRenderer.renderProcessLines("not-json")).isEmpty();
    }

    private static ProcessingStep withReasoning(ProcessingStep base, String reasoning) {
        return new ProcessingStep(
                base.id(), base.phase(), base.lifecycle(), base.summary(), base.startedAt(),
                base.endedAt(), base.durationMs(), base.detail(), reasoning,
                base.output(), base.result(), base.ts(), base.label(), base.metadata(),
                base.contentBlocks(), base.subSteps(), base.stepSummary());
    }
}
