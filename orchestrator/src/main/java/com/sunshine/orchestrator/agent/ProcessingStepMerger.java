package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.ContentBlock;
import com.sunshine.orchestrator.processing.StepLabels;
import com.sunshine.orchestrator.processing.StepSummary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 处理步骤 upsert / delta 合并 */
public final class ProcessingStepMerger {

    private ProcessingStepMerger() {
    }

    public static void upsert(List<ProcessingStep> steps, ProcessingStep incoming) {
        if (incoming == null) {
            return;
        }
        Map<String, ProcessingStep> byId = new LinkedHashMap<>();
        for (ProcessingStep step : steps) {
            byId.put(step.id(), step);
        }
        ProcessingStep existing = byId.get(incoming.id());
        byId.put(incoming.id(), existing == null ? incoming : mergeSteps(existing, incoming));
        steps.clear();
        steps.addAll(byId.values());
    }

    public static void applyDelta(List<ProcessingStep> steps, String stepId, String channel, String text) {
        if (stepId == null || channel == null || text == null || text.isEmpty()) {
            return;
        }
        Map<String, ProcessingStep> byId = new LinkedHashMap<>();
        for (ProcessingStep step : steps) {
            byId.put(step.id(), step);
        }
        ProcessingStep existing = byId.get(stepId);
        if (existing == null) {
            existing = ProcessingStep.running(stepId, stepId, StepLabels.labelFor(stepId));
        }
        byId.put(stepId, applyDeltaToStep(existing, channel, text));
        steps.clear();
        steps.addAll(byId.values());
    }

    private static ProcessingStep applyDeltaToStep(ProcessingStep step, String channel, String text) {
        return switch (channel) {
            case "reasoning" -> copyStep(step,
                    appendReasoning(step.reasoning(), text),
                    step.output(),
                    step.result());
            case "output" -> copyStep(step,
                    step.reasoning(),
                    concat(step.output(), text),
                    step.result());
            case "result" -> copyStep(step, step.reasoning(), step.output(), concat(step.result(), text));
            case "step_summary" -> copyStepWithSummary(step, text);
            default -> copyStep(step, step.reasoning(), concat(step.output(), text), step.result());
        };
    }

    /** ReAct reasoning 已由 Hook 原生 incrementalChunk 保证为真增量 */
    public static String appendReasoning(String existing, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return existing;
        }
        if (existing == null || existing.isEmpty()) {
            return chunk;
        }
        return existing + chunk;
    }

    /**
     * done 态 reasoning 为全量覆盖；running 态用前缀合并。
     * SubAgent/Loop 折叠会反复 upsert「已累积全文」的 step 快照，禁止再按增量 append（否则指数膨胀打爆 SSE）。
     */
    private static String mergeReasoning(ProcessingStep existing, ProcessingStep incoming) {
        if (incoming.reasoning() == null) {
            return existing.reasoning();
        }
        if (isDone(incoming)) {
            return incoming.reasoning();
        }
        return longer(existing.reasoning(), incoming.reasoning());
    }

    private static boolean isDone(ProcessingStep step) {
        return "done".equals(step.lifecycle());
    }

    /**
     * 终态（含用户取消 paused+after）不被后续 running/pending/done/error 覆盖。
     * 与前端 resolveMergedLifecycle 对齐，避免 cancel 后 complete 竞态盖掉 paused。
     */
    private static String mergeLifecycle(ProcessingStep existing, ProcessingStep incoming) {
        String next = incoming.lifecycle() != null ? incoming.lifecycle() : existing.lifecycle();
        if (isCancelTerminal(existing)
                && ("running".equals(next) || "pending".equals(next)
                || "done".equals(next) || "error".equals(next))) {
            return existing.lifecycle();
        }
        if (isHardTerminal(existing)
                && ("running".equals(next) || "pending".equals(next))) {
            return existing.lifecycle();
        }
        return next;
    }

    private static boolean isHardTerminal(ProcessingStep step) {
        if (step == null || step.lifecycle() == null) {
            return false;
        }
        String lc = step.lifecycle();
        return "done".equals(lc) || "error".equals(lc) || "skipped".equals(lc) || "terminated".equals(lc);
    }

    private static boolean isCancelTerminal(ProcessingStep step) {
        return step != null
                && "paused".equals(step.lifecycle())
                && step.summary() != null
                && step.summary().after() != null
                && !step.summary().after().isBlank();
    }

    private static String concat(String existing, String chunk) {
        if (existing == null || existing.isEmpty()) {
            return chunk;
        }
        if (chunk == null || chunk.isEmpty()) {
            return existing;
        }
        return existing + chunk;
    }

    private static ProcessingStep copyStep(
            ProcessingStep step, String reasoning, String output, String result) {
        return copyStep(step, reasoning, output, result, step.stepSummary());
    }

    /** step_summary 通道：仅覆盖摘要字段，不动 reasoning/output/result */
    private static ProcessingStep copyStepWithSummary(ProcessingStep step, String summary) {
        return copyStep(step, step.reasoning(), step.output(), step.result(), summary);
    }

    private static ProcessingStep copyStep(
            ProcessingStep step, String reasoning, String output, String result, String stepSummary) {
        return new ProcessingStep(
                step.id(),
                step.phase(),
                step.lifecycle() != null ? step.lifecycle() : "running",
                step.summary(),
                step.startedAt(),
                step.endedAt(),
                step.durationMs(),
                step.detail(),
                reasoning,
                output,
                result,
                step.ts(),
                step.label(),
                step.metadata(),
                step.contentBlocks(),
                step.subSteps(),
                stepSummary
        );
    }

    /** done 态 step 的 result 为全量终稿，覆盖流式 delta 累积 */
    private static String mergeResult(ProcessingStep existing, ProcessingStep incoming) {
        if (isDone(incoming) && incoming.result() != null && !incoming.result().isEmpty()) {
            return incoming.result();
        }
        return longer(existing.result(), incoming.result());
    }

    private static String longer(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        if (b.startsWith(a)) {
            return b;
        }
        if (a.startsWith(b)) {
            return a;
        }
        return a + b;
    }

    private static ProcessingStep mergeSteps(ProcessingStep existing, ProcessingStep incoming) {
        Long startedAt = minNonNull(existing.startedAt(), incoming.startedAt());
        String lifecycle = mergeLifecycle(existing, incoming);
        StepSummary summary = isCancelTerminal(existing) && "paused".equals(lifecycle)
                ? existing.summary()
                : mergeSummary(existing.summary(), incoming.summary());
        Long endedAt = moreComplete(existing.endedAt(), incoming.endedAt());
        Long durationMs = computeDuration(startedAt, endedAt,
                existing.durationMs(), incoming.durationMs());

        return new ProcessingStep(
                incoming.id(),
                incoming.phase() != null ? incoming.phase() : existing.phase(),
                lifecycle,
                summary,
                startedAt,
                endedAt,
                durationMs,
                incoming.detail() != null ? incoming.detail() : existing.detail(),
                mergeReasoning(existing, incoming),
                longer(existing.output(), incoming.output()),
                mergeResult(existing, incoming),
                Math.max(existing.ts(), incoming.ts()),
                incoming.label() != null ? incoming.label() : existing.label(),
                incoming.metadata() != null ? mergeMetadata(existing.metadata(), incoming.metadata()) : existing.metadata(),
                mergeContentBlocks(existing.contentBlocks(), incoming.contentBlocks()),
                mergeSubSteps(existing.subSteps(), incoming.subSteps()),
                incoming.stepSummary() != null ? incoming.stepSummary() : existing.stepSummary()
        );
    }

    private static List<ContentBlock> mergeContentBlocks(List<ContentBlock> existing, List<ContentBlock> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return existing;
        }
        return incoming;
    }

    public static void setStepContentBlocks(List<ProcessingStep> steps, String stepId, List<ContentBlock> blocks) {
        if (steps == null || stepId == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        if (setStepContentBlocksInList(steps, stepId, blocks)) {
            return;
        }
    }

    private static boolean setStepContentBlocksInList(
            List<ProcessingStep> steps, String stepId, List<ContentBlock> blocks) {
        for (int i = 0; i < steps.size(); i++) {
            ProcessingStep step = steps.get(i);
            if (stepId.equals(step.id())) {
                steps.set(i, copyWithContentBlocks(step, blocks));
                return true;
            }
            List<ProcessingStep> subs = step.subSteps();
            if (subs != null && !subs.isEmpty()) {
                List<ProcessingStep> copy = new ArrayList<>(subs);
                if (setStepContentBlocksInList(copy, stepId, blocks)) {
                    steps.set(i, copyWithSubSteps(step, copy));
                    return true;
                }
            }
        }
        return false;
    }

    private static ProcessingStep copyWithSubSteps(ProcessingStep step, List<ProcessingStep> subSteps) {
        return new ProcessingStep(
                step.id(),
                step.phase(),
                step.lifecycle(),
                step.summary(),
                step.startedAt(),
                step.endedAt(),
                step.durationMs(),
                step.detail(),
                step.reasoning(),
                step.output(),
                step.result(),
                step.ts(),
                step.label(),
                step.metadata(),
                step.contentBlocks(),
                subSteps,
                step.stepSummary());
    }

    private static List<ProcessingStep> mergeSubSteps(
            List<ProcessingStep> existing,
            List<ProcessingStep> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return existing;
        }
        List<ProcessingStep> merged = existing != null && !existing.isEmpty()
                ? new ArrayList<>(existing)
                : new ArrayList<>();
        for (ProcessingStep step : incoming) {
            upsert(merged, step);
        }
        return merged;
    }

    private static com.sunshine.orchestrator.processing.StepMetadata mergeMetadata(
            com.sunshine.orchestrator.processing.StepMetadata existing,
            com.sunshine.orchestrator.processing.StepMetadata incoming) {
        return com.sunshine.orchestrator.processing.StepMetadata.merge(existing, incoming);
    }

    private static StepSummary mergeSummary(StepSummary existing, StepSummary incoming) {
        if (existing == null) {
            return incoming;
        }
        if (incoming == null) {
            return existing;
        }
        return new StepSummary(
                incoming.before() != null ? incoming.before() : existing.before(),
                incoming.active() != null ? incoming.active() : existing.active(),
                incoming.after() != null ? incoming.after() : existing.after()
        );
    }

    private static Long minNonNull(Long a, Long b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.min(a, b);
    }

    private static Long moreComplete(Long existing, Long incoming) {
        return incoming != null ? incoming : existing;
    }

    private static Long computeDuration(Long startedAt, Long endedAt, Long existing, Long incoming) {
        if (startedAt != null && endedAt != null) {
            return endedAt - startedAt;
        }
        return incoming != null ? incoming : existing;
    }

    private static ProcessingStep copyWithContentBlocks(ProcessingStep step, List<ContentBlock> contentBlocks) {
        return new ProcessingStep(
                step.id(),
                step.phase(),
                step.lifecycle(),
                step.summary(),
                step.startedAt(),
                step.endedAt(),
                step.durationMs(),
                step.detail(),
                step.reasoning(),
                step.output(),
                step.result(),
                step.ts(),
                step.label(),
                step.metadata(),
                contentBlocks,
                step.subSteps(),
                step.stepSummary());
    }
}
