package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.routing.ExecutionMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 LLM reasoning 拆为独立 {@code think} 步骤；正文走 {@code generate}。
 * 所有路径（直连 LLM / ReActAgent）统一语义，不再使用 {@code agent} 容器步。
 */
public final class ThinkStepMapper {

    private static final String GENERATE = "generate";

    private final List<ProcessingStep> stepsBuffer;
    private final String userQuery;
    private final AtomicReference<ExecutionMode> executionMode;

    /** 当前 opened 的 think 步骤 id（支持 think / think-2 …） */
    private String activeThinkId;
    private boolean generateOpened;
    /** Workflow 路径已有 plan/node 步骤，正文不再单独开 generate */
    private boolean workflowMode;

    public ThinkStepMapper(List<ProcessingStep> stepsBuffer, String userQuery) {
        this(stepsBuffer, userQuery, new AtomicReference<>(ExecutionMode.REACT));
    }

    public ThinkStepMapper(List<ProcessingStep> stepsBuffer, String userQuery,
            AtomicReference<ExecutionMode> executionMode) {
        this.stepsBuffer = stepsBuffer != null ? stepsBuffer : new ArrayList<>();
        this.userQuery = userQuery;
        this.executionMode = executionMode != null
                ? executionMode
                : new AtomicReference<>(ExecutionMode.REACT);
        for (ProcessingStep step : this.stepsBuffer) {
            trackExistingStep(step);
        }
    }

    private ExecutionMode mode() {
        ExecutionMode current = executionMode.get();
        return current != null ? current : ExecutionMode.REACT;
    }

    public List<StreamToken> map(StreamToken token) {
        if (token == null) {
            return List.of();
        }
        if (token.isStep()) {
            trackExistingStep(token.step());
            return List.of(token);
        }
        if (token.isContentStart() || token.isContentEnd()) {
            if (mode() == ExecutionMode.REACT) {
                return List.of(token);
            }
            return List.of();
        }
        if (token.isStepDelta()) {
            return mapStepDelta(token);
        }
        if (token.isReasoning()) {
            return mapReasoning(token.text());
        }
        if (token.isContent()) {
            if (mode() == ExecutionMode.REACT && token.segmentId() != null) {
                return List.of(token);
            }
            return mapContent(token);
        }
        return List.of();
    }

    /** 流结束时补齐 think / generate 的完成态 */
    public List<StreamToken> finish() {
        return finish(false);
    }

    /** @param streamFailed 异常/中断时不伪造空的 generate 步骤 */
    public List<StreamToken> finish(boolean streamFailed) {
        List<StreamToken> out = new ArrayList<>();
        findRunningThinkId().ifPresent(id -> out.add(stepToken(completeThinkStep(id))));
        if (mode() == ExecutionMode.REACT) {
            return out;
        }
        if (!streamFailed && !workflowMode && !generateOpened && !hasStep(GENERATE)) {
            out.addAll(openGenerate());
        }
        if (!streamFailed && !workflowMode && generateOpened && isRunning(GENERATE)) {
            out.add(stepToken(completeGenerateStep()));
        }
        return out;
    }

    private List<StreamToken> mapStepDelta(StreamToken token) {
        if (!"reasoning".equals(token.channel())) {
            return List.of(token);
        }
        String text = token.text();
        if (text == null || text.isEmpty()) {
            return List.of(token);
        }
        String stepId = token.stepId();
        if (ThinkStepIds.isThinkStep(stepId)) {
            List<StreamToken> out = new ArrayList<>(openThinkIfNeeded(stepId));
            out.add(token);
            return out;
        }
        if (stepId != null && stepId.startsWith("node-")) {
            return List.of(token);
        }
        return mapReasoning(text);
    }

    private List<StreamToken> mapReasoning(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String thinkId = resolveThinkIdForReasoning();
        List<StreamToken> out = new ArrayList<>(openThinkIfNeeded(thinkId));
        out.add(StreamToken.stepDelta(thinkId, "reasoning", text));
        return out;
    }

    private List<StreamToken> mapContent(StreamToken token) {
        if (workflowMode) {
            return List.of(token);
        }
        if (mode() == ExecutionMode.REACT) {
            if (token.afterStepId() != null) {
                return List.of(token);
            }
            String anchor = findLastDoneThinkId();
            return List.of(StreamToken.content(token.text(), anchor));
        }
        List<StreamToken> out = new ArrayList<>(transitionToGenerate());
        out.add(token);
        return out;
    }

    private List<StreamToken> transitionToGenerate() {
        List<StreamToken> out = new ArrayList<>();
        findRunningThinkId().ifPresent(id -> out.add(stepToken(completeThinkStep(id))));
        out.addAll(openGenerate());
        return out;
    }

    private List<StreamToken> openThinkIfNeeded(String stepId) {
        if (stepId == null || hasStep(stepId)) {
            return List.of();
        }
        activeThinkId = stepId;
        ProcessingStep step = runningThinkStep(stepId);
        ProcessingStepMerger.upsert(stepsBuffer, step);
        return List.of(stepToken(step));
    }

    /** 直连 LLM 路径：复用 running think，否则开下一轮 */
    private String resolveThinkIdForReasoning() {
        return findRunningThinkId().orElseGet(this::nextThinkId);
    }

    private String nextThinkId() {
        int max = 0;
        for (ProcessingStep step : stepsBuffer) {
            if (!ThinkStepIds.isThinkStep(step.id())) {
                continue;
            }
            if ("think".equals(step.id())) {
                max = Math.max(max, 1);
            } else if (step.id().startsWith("think-")) {
                try {
                    max = Math.max(max, Integer.parseInt(step.id().substring("think-".length())));
                } catch (NumberFormatException ignored) {
                    // 非标准 id 忽略
                }
            }
        }
        return ThinkStepIds.forIteration(max + 1);
    }

    private java.util.Optional<String> findRunningThinkId() {
        for (ProcessingStep step : stepsBuffer) {
            if (ThinkStepIds.isThinkStep(step.id()) && isRunning(step.id())) {
                return java.util.Optional.of(step.id());
            }
        }
        if (activeThinkId != null && isRunning(activeThinkId)) {
            return java.util.Optional.of(activeThinkId);
        }
        return java.util.Optional.empty();
    }

    private String findLastDoneThinkId() {
        String last = null;
        for (ProcessingStep step : stepsBuffer) {
            if (ThinkStepIds.isThinkStep(step.id()) && "done".equals(step.lifecycle())) {
                last = step.id();
            }
        }
        return last;
    }

    private List<StreamToken> openGenerate() {
        if (generateOpened || hasStep(GENERATE)) {
            return List.of();
        }
        generateOpened = true;
        ProcessingStep step = runningGenerateStep();
        ProcessingStepMerger.upsert(stepsBuffer, step);
        return List.of(stepToken(step));
    }

    private ProcessingStep runningThinkStep(String stepId) {
        long ts = System.currentTimeMillis();
        String label = ThinkStepIds.displayLabel(stepId, mode());
        StepSummary summary = new StepSummary(
                summarizeBefore(stepId),
                summarizeActive(stepId),
                null);
        return new ProcessingStep(
                stepId, "think", "running", summary,
                ts, null, null, null,
                null, null, null,
                ts, label, null, null, null);
    }

    private ProcessingStep completeThinkStep(String stepId) {
        long ts = System.currentTimeMillis();
        ProcessingStep prev = findStep(stepId);
        long startedAt = prev != null && prev.startedAt() != null ? prev.startedAt() : ts;
        String label = ThinkStepIds.displayLabel(stepId, mode());
        String after = summarizeAfter(stepId);
        StepSummary summary = mergeSummary(prev, new StepSummary(
                summarizeBefore(stepId),
                summarizeActive(stepId),
                after));
        // reasoning 已由 step_delta 流式下发，终态 step 勿重复携带全文
        return new ProcessingStep(
                stepId, "think", "done", summary,
                startedAt, ts, ts - startedAt, null,
                null,
                prev != null ? prev.output() : null,
                prev != null ? prev.result() : null,
                ts, label, null, null, null);
    }

    private ProcessingStep runningGenerateStep() {
        long ts = System.currentTimeMillis();
        String label = StepLabels.labelFor(GENERATE);
        StepSummary summary = new StepSummary(
                summarizeBefore(GENERATE),
                summarizeActive(GENERATE),
                null);
        return new ProcessingStep(
                GENERATE, GENERATE, "running", summary,
                ts, null, null, null,
                null, null, null,
                ts, label, null, null, null);
    }

    private ProcessingStep completeGenerateStep() {
        long ts = System.currentTimeMillis();
        ProcessingStep prev = findStep(GENERATE);
        long startedAt = prev != null && prev.startedAt() != null ? prev.startedAt() : ts;
        String label = StepLabels.labelFor(GENERATE);
        String after = summarizeAfter(GENERATE);
        StepSummary summary = mergeSummary(prev, new StepSummary(
                summarizeBefore(GENERATE),
                summarizeActive(GENERATE),
                after));
        return new ProcessingStep(
                GENERATE, GENERATE, "done", summary,
                startedAt, ts, ts - startedAt, null,
                prev != null ? prev.reasoning() : null,
                prev != null ? prev.output() : null,
                prev != null ? prev.result() : null,
                ts, label, null, null, null);
    }

    private void trackExistingStep(ProcessingStep step) {
        if (step == null) {
            return;
        }
        if (ThinkStepIds.isThinkStep(step.id())) {
            activeThinkId = step.id();
        }
        if (GENERATE.equals(step.id())) {
            generateOpened = true;
        }
        if ("plan".equals(step.id())
                || (step.id() != null && step.id().startsWith("node-"))) {
            workflowMode = true;
            generateOpened = true;
        }
    }

    /** ReAct / Workflow 已内联 step 时同步状态，避免 finish 重复开 generate */
    public void syncExternalStep(ProcessingStep step) {
        trackExistingStep(step);
    }

    private boolean isRunning(String stepId) {
        ProcessingStep step = findStep(stepId);
        if (step == null) {
            return false;
        }
        return "running".equals(step.lifecycle());
    }

    private boolean hasStep(String stepId) {
        return findStep(stepId) != null;
    }

    private ProcessingStep findStep(String stepId) {
        for (ProcessingStep step : stepsBuffer) {
            if (stepId.equals(step.id())) {
                return step;
            }
        }
        return null;
    }

    private static StreamToken stepToken(ProcessingStep step) {
        return StreamToken.step(step);
    }

    private String summarizeBefore(String stepId) {
        return userQuery != null && !userQuery.isBlank()
                ? StepSummarizer.before(stepId, userQuery, null, mode())
                : StepSummarizer.beforeFallback(stepId);
    }

    private String summarizeActive(String stepId) {
        return userQuery != null && !userQuery.isBlank()
                ? StepSummarizer.active(stepId, userQuery, null, mode())
                : StepSummarizer.activeFallback(stepId);
    }

    private String summarizeAfter(String stepId) {
        return userQuery != null && !userQuery.isBlank()
                ? StepSummarizer.after(stepId, userQuery, null, null, mode())
                : StepSummarizer.afterFallback(stepId, null);
    }

    private static StepSummary mergeSummary(ProcessingStep prev, StepSummary incoming) {
        if (prev == null || prev.summary() == null) {
            return incoming;
        }
        StepSummary existing = prev.summary();
        return new StepSummary(
                incoming.before() != null ? incoming.before() : existing.before(),
                incoming.active() != null ? incoming.active() : existing.active(),
                incoming.after() != null ? incoming.after() : existing.after());
    }
}
