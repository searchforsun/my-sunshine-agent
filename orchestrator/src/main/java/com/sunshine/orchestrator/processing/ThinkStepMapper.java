package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.client.StreamToken;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 LLM reasoning 拆为独立 {@code think} 步骤，置于 intent 与 generate 之间。
 * Agent 路径仍由 {@code agent} 步骤承载思考内容。
 */
public final class ThinkStepMapper {

    private static final String THINK = "think";
    private static final String GENERATE = "generate";
    private static final String AGENT = "agent";
    private static final String RAG = "rag";

    private final List<ProcessingStep> stepsBuffer;
    private final String userQuery;

    private boolean thinkOpened;
    private boolean generateOpened;

    public ThinkStepMapper(List<ProcessingStep> stepsBuffer, String userQuery) {
        this.stepsBuffer = stepsBuffer != null ? stepsBuffer : new ArrayList<>();
        this.userQuery = userQuery;
    }

    public List<StreamToken> map(StreamToken token) {
        if (token == null) {
            return List.of();
        }
        if (token.isStep()) {
            trackExistingStep(token.step());
            return List.of(token);
        }
        if (token.isStepDelta()) {
            return mapStepDelta(token);
        }
        if (token.isReasoning()) {
            return mapReasoning(token.text());
        }
        if (token.isContent()) {
            return mapContent(token);
        }
        return List.of(token);
    }

    /** 流结束时补齐 think / generate 的完成态 */
    public List<StreamToken> finish() {
        List<StreamToken> out = new ArrayList<>();
        if (thinkOpened && isRunning(THINK)) {
            out.add(stepToken(completeThinkStep()));
        }
        if (!generateOpened && !hasStep(GENERATE)) {
            out.addAll(openGenerate());
        }
        if (generateOpened && isRunning(GENERATE)) {
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
        if (AGENT.equals(token.stepId()) || RAG.equals(token.stepId())) {
            return List.of(token);
        }
        String target = reasoningTarget();
        if (target == null) {
            return List.of(token);
        }
        if (!target.equals(token.stepId())) {
            return mapReasoning(text);
        }
        List<StreamToken> out = new ArrayList<>(openThinkIfNeeded());
        out.add(StreamToken.stepDelta(target, "reasoning", text));
        return out;
    }

    private List<StreamToken> mapReasoning(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String target = reasoningTarget();
        if (target == null) {
            return List.of(StreamToken.reasoning(text));
        }
        List<StreamToken> out = new ArrayList<>(openThinkIfNeeded());
        out.add(StreamToken.stepDelta(target, "reasoning", text));
        return out;
    }

    private List<StreamToken> mapContent(StreamToken token) {
        List<StreamToken> out = new ArrayList<>(transitionToGenerate());
        out.add(token);
        return out;
    }

    private List<StreamToken> transitionToGenerate() {
        List<StreamToken> out = new ArrayList<>();
        if (thinkOpened && isRunning(THINK)) {
            out.add(stepToken(completeThinkStep()));
        }
        out.addAll(openGenerate());
        return out;
    }

    private List<StreamToken> openThinkIfNeeded() {
        if (isRunning(AGENT) || thinkOpened || hasStep(THINK)) {
            return List.of();
        }
        thinkOpened = true;
        ProcessingStep step = runningThinkStep();
        ProcessingStepMerger.upsert(stepsBuffer, step);
        return List.of(stepToken(step));
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

    private String reasoningTarget() {
        if (isRunning(AGENT)) {
            return AGENT;
        }
        if (isRunning(GENERATE) && !thinkOpened && !hasStep(THINK)) {
            return GENERATE;
        }
        return THINK;
    }

    private ProcessingStep runningThinkStep() {
        long ts = System.currentTimeMillis();
        String label = StepLabels.labelFor(THINK);
        StepSummary summary = new StepSummary(
                summarizeBefore(THINK),
                summarizeActive(THINK),
                null);
        return new ProcessingStep(
                THINK, THINK, "running", summary,
                ts, null, null, null,
                null, null, null,
                ts, "running", label);
    }

    private ProcessingStep completeThinkStep() {
        long ts = System.currentTimeMillis();
        ProcessingStep prev = findStep(THINK);
        long startedAt = prev != null && prev.startedAt() != null ? prev.startedAt() : ts;
        String label = StepLabels.labelFor(THINK);
        String after = summarizeAfter(THINK);
        StepSummary summary = mergeSummary(prev, new StepSummary(
                summarizeBefore(THINK),
                summarizeActive(THINK),
                after));
        return new ProcessingStep(
                THINK, THINK, "done", summary,
                startedAt, ts, ts - startedAt, null,
                prev != null ? prev.reasoning() : null,
                prev != null ? prev.output() : null,
                prev != null ? prev.result() : null,
                ts, "done", label);
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
                ts, "running", label);
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
                ts, "done", label);
    }

    private void trackExistingStep(ProcessingStep step) {
        if (step == null) {
            return;
        }
        if (THINK.equals(step.id())) {
            thinkOpened = true;
        }
        if (GENERATE.equals(step.id())) {
            generateOpened = true;
        }
    }

    private boolean isRunning(String stepId) {
        ProcessingStep step = findStep(stepId);
        if (step == null) {
            return false;
        }
        if ("running".equals(step.lifecycle())) {
            return true;
        }
        return "running".equals(step.status());
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
                ? StepSummarizer.before(stepId, userQuery)
                : StepSummarizer.beforeFallback(stepId);
    }

    private String summarizeActive(String stepId) {
        return userQuery != null && !userQuery.isBlank()
                ? StepSummarizer.active(stepId, userQuery)
                : StepSummarizer.activeFallback(stepId);
    }

    private String summarizeAfter(String stepId) {
        return userQuery != null && !userQuery.isBlank()
                ? StepSummarizer.after(stepId, userQuery, null)
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
