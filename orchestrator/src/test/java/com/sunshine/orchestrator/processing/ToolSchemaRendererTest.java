package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具轮确定性 schema 行渲染（五层 §5.5.8 / task-scene §6.5）：
 * steps JSON → 固定格式行，零 LLM、原样保留工具字段。
 */
class ToolSchemaRendererTest {

    @Test
    void execStep_rendersKeyArgsStatusExitResultRefs() {
        ProcessingStep step = ProcessingStep.done(
                        "tool-sandbox__exec@1710000000000", "tool", "执行命令", "exit 0")
                .withMetadata(StepMetadata.withToolSchema(
                        null, "金额=3000 · 单据类型=报销", 0));
        StepMetadata withPath = withSandboxPath(step.metadata(), "/workspace/apply/order-1.json");

        String line = ToolSchemaRenderer.renderSchemaLine(step.withMetadata(withPath));

        assertThat(line).isEqualTo(
                "[sandbox__exec] keyArgs=金额=3000 · 单据类型=报销 status=ok exit=0"
                        + " · result=exit 0 · refs=[/workspace/apply/order-1.json]");
    }

    private static StepMetadata withSandboxPath(StepMetadata base, String path) {
        return new StepMetadata(
                base.hitCount(), base.sources(), base.rewriteApplied(), base.rewriteLatencyMs(),
                base.rewriteFrom(), base.rewriteTo(), base.rewriteScenario(), base.rewriteScenarioLabel(),
                base.skillId(), base.plannerMode(), base.routingReason(), base.rewriteInDetail(),
                base.expandSectionTitle(), base.hitl(), base.recovery(), base.nodeAttempts(),
                base.tasks(), base.taskRevision(), base.taskProgress(),
                path, base.sandboxSearchRoot(), base.spawnPrompt(), base.cancellable(),
                base.editDiff(), base.decision(), base.routingTraces(), base.workerRunId(), base.taskQueue(),
                base.toolArgs(), base.toolExitCode());
    }

    @Test
    void writeStep_omitsResult_keepsRefs() {
        // 写/改类工具禁止带 patch 原文进 Mid：result 省略，仅 refs
        ProcessingStep step = ProcessingStep.done(
                        "tool-sandbox__write@1710000000001", "tool", "写入文件",
                        "写入 1024 字节到 /workspace/a.py")
                .withMetadata(StepMetadata.withToolSchema(null, "path=/workspace/a.py", null));
        StepMetadata withPath = withSandboxPath(step.metadata(), "/workspace/a.py");

        String line = ToolSchemaRenderer.renderSchemaLine(step.withMetadata(withPath));

        assertThat(line).isEqualTo(
                "[sandbox__write] keyArgs=path=/workspace/a.py status=ok"
                        + " · refs=[/workspace/a.py]");
    }

    @Test
    void errorStep_mapsToFail_keepsExitCode() {
        ProcessingStep step = ProcessingStep.error(
                        "tool-sandbox__exec@1710000000002", "tool", "执行命令", "Command failed: 127")
                .withMetadata(StepMetadata.withToolSchema(null, "cmd=ls /nonexist", 127));

        String line = ToolSchemaRenderer.renderSchemaLine(step);

        assertThat(line).isEqualTo(
                "[sandbox__exec] keyArgs=cmd=ls /nonexist status=fail exit=127"
                        + " · result=Command failed: 127");
    }

    @Test
    void nonToolStep_returnsNull() {
        ProcessingStep think = ProcessingStep.done("think@1710000000003", "think", "思考", "分析中");

        assertThat(ToolSchemaRenderer.renderSchemaLine(think)).isNull();
    }

    @Test
    void runningStep_skipped() {
        ProcessingStep running = ProcessingStep.running("tool-sandbox__exec@1710000000004", "tool", "执行中");

        assertThat(ToolSchemaRenderer.renderSchemaLine(running)).isNull();
    }

    @Test
    void pausedStep_mapsToDenied() {
        ProcessingStep paused = ProcessingStep.done("tool-sandbox__exec@1710000000005", "tool", "被取消", "已取消")
                .withMetadata(StepMetadata.withToolSchema(null, null, null));
        // 直接改 lifecycle 模拟 paused（复用 done 结构 + 覆写终态）
        ProcessingStep pausedStep = new ProcessingStep(
                paused.id(), paused.phase(), "paused", paused.summary(), paused.startedAt(),
                paused.endedAt(), paused.durationMs(), paused.detail(), paused.reasoning(),
                paused.output(), paused.result(), paused.ts(), paused.label(), paused.metadata(),
                paused.contentBlocks(), paused.subSteps(), paused.stepSummary());

        String line = ToolSchemaRenderer.renderSchemaLine(pausedStep);

        assertThat(line).isEqualTo("[sandbox__exec] status=denied · result=已取消");
    }

    @Test
    void ragStep_mapsCatalogName() {
        ProcessingStep step = ProcessingStep.done(
                        "rag@1710000000006", "rag", "检索知识", "命中 3 条")
                .withMetadata(StepMetadata.withToolSchema(null, "query=报销流程", null));

        String line = ToolSchemaRenderer.renderSchemaLine(step);

        assertThat(line).isEqualTo("[search_knowledge] keyArgs=query=报销流程 status=ok · result=命中 3 条");
    }

    @Test
    void result_truncatedTo200() {
        String longDetail = "x".repeat(500);
        ProcessingStep step = ProcessingStep.done(
                        "tool-sandbox__exec@1710000000007", "tool", "执行", longDetail)
                .withMetadata(StepMetadata.withToolSchema(null, null, null));

        String line = ToolSchemaRenderer.renderSchemaLine(step);

        assertThat(line).startsWith("[sandbox__exec] status=ok · result=");
        assertThat(line).contains("…");
        assertThat(line.length()).isLessThan(260);
    }

    @Test
    void renderSchemaLines_fromJson_skipsNonToolAndEmpty() {
        ProcessingStep tool = ProcessingStep.done(
                        "tool-sandbox__exec@1710000000008", "tool", "执行", "ok")
                .withMetadata(StepMetadata.withToolSchema(null, "amount=100", 0));
        ProcessingStep think = ProcessingStep.done("think@1710000000009", "think", "思考", "分析");
        String json = ProcessingStepSerde.toJson(List.of(tool, think));

        List<String> lines = ToolSchemaRenderer.renderSchemaLines(json);

        assertThat(lines).containsExactly(
                "[sandbox__exec] keyArgs=amount=100 status=ok exit=0 · result=ok");
    }

    @Test
    void renderSchemaLines_blankJson_returnsEmpty() {
        assertThat(ToolSchemaRenderer.renderSchemaLines(null)).isEmpty();
        assertThat(ToolSchemaRenderer.renderSchemaLines("   ")).isEmpty();
        assertThat(ToolSchemaRenderer.renderSchemaLines("not-json")).isEmpty();
    }

    @Test
    void renderProcessLine_writeKeepsFullDetail() {
        // task Near 完整过程（§6.6）：写/改类保留输出原文（完整 patch/代码），不进 schema 截断
        String patch = "--- a/a.py\n+++ b/a.py\n@@ -1,3 +1,3 @@\n print(1)\n+print(2)";
        ProcessingStep step = ProcessingStep.done(
                        "tool-sandbox__edit@1710000000010", "tool", "修改文件", patch)
                .withMetadata(StepMetadata.withToolSchema(null, "path=/workspace/a.py", null));

        String line = ToolSchemaRenderer.renderProcessLine(step);

        assertThat(line).isEqualTo(
                "[sandbox__edit] keyArgs=path=/workspace/a.py status=ok"
                        + " · result=" + patch);
    }

    @Test
    void renderProcessLine_execStillTruncated() {
        // 执行/读类在 Near 完整过程仍机械截断 ≤200（§6.6 结果分级）
        String longDetail = "y".repeat(500);
        ProcessingStep step = ProcessingStep.done(
                        "tool-sandbox__exec@1710000000011", "tool", "执行", longDetail)
                .withMetadata(StepMetadata.withToolSchema(null, "cmd=pytest", 0));

        String line = ToolSchemaRenderer.renderProcessLine(step);

        assertThat(line).startsWith("[sandbox__exec] keyArgs=cmd=pytest status=ok exit=0 · result=");
        assertThat(line).contains("…");
        assertThat(line.length()).isLessThan(280);
    }
}
