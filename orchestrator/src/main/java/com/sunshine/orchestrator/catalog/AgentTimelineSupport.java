package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.StepSummary;

import java.util.List;
import java.util.stream.Collectors;

public final class AgentTimelineSupport {
    private AgentTimelineSupport() {
    }

    public static String speakStepId(ExpertTranscriptEntry entry) {
        return "expert-" + entry.expertId() + "-s" + entry.speakSeq();
    }

    public static StreamToken conveneRunning(long startedAt) {
        StepSummary summary = new StepSummary(
                AgentStepLabels.conveneBefore(),
                AgentStepLabels.conveneActive(),
                null);
        ProcessingStep step = new ProcessingStep(
                "expert-convene",
                "expert-convene",
                "running",
                summary,
                startedAt, null, null,
                null, null, null, null,
                startedAt, AgentStepLabels.conveneLabel(),
                null, null, null);
        return StreamToken.step(step);
    }

    public static StreamToken conveneDone(long startedAt, List<String> displayNames, String coordinatorReason) {
        long endedAt = System.currentTimeMillis();
        String names = displayNames.stream().collect(Collectors.joining("、"));
        String after = AgentStepLabels.conveneAfter(names);
        StepSummary summary = new StepSummary(
                AgentStepLabels.conveneBefore(),
                AgentStepLabels.conveneActive(),
                after);
        String detail = coordinatorReason != null ? coordinatorReason : "";
        ProcessingStep step = new ProcessingStep(
                "expert-convene",
                "expert-convene",
                "done",
                summary,
                startedAt, endedAt, Math.max(0L, endedAt - startedAt),
                detail.isBlank() ? null : detail,
                null, null, null,
                endedAt, AgentStepLabels.conveneLabel(),
                null, null, null);
        return StreamToken.step(step);
    }

    public static StreamToken speakRunning(ExpertTranscriptEntry entry, boolean responding, long startedAt) {
        return speak(entry, "running", responding, startedAt, null, null, null);
    }

    /** 刷新 running 态主行文案（如内部工具调用中） */
    public static StreamToken speakActive(
            ExpertTranscriptEntry entry, String active, boolean responding, long startedAt) {
        String after = null;
        StepSummary summary = new StepSummary(
                AgentStepLabels.expertBefore(entry.displayName()),
                active,
                after);
        String stepId = speakStepId(entry);
        ProcessingStep step = new ProcessingStep(
                stepId,
                "expert",
                "running",
                summary,
                startedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                startedAt,
                AgentStepLabels.expertLabel(entry.displayName()),
                null,
                null,
                null);
        return StreamToken.step(step);
    }

    public static StreamToken speakDone(
            ExpertTranscriptEntry entry,
            boolean responding,
            long startedAt,
            String result) {
        long endedAt = System.currentTimeMillis();
        return speak(entry, "done", responding, startedAt, endedAt, Math.max(0L, endedAt - startedAt), result);
    }

    public static StreamToken speakDelta(String stepId, String text) {
        return StreamToken.stepDelta(stepId, "result", text);
    }

    private static StreamToken speak(
            ExpertTranscriptEntry entry,
            String lifecycle,
            boolean responding,
            long startedAt,
            Long endedAt,
            Long durationMs,
            String result) {
        String active = responding
                ? AgentStepLabels.expertActiveResponding(entry.displayName())
                : AgentStepLabels.expertActive(entry.displayName());
        String after = lifecycle.equals("done")
                ? AgentStepLabels.expertAfter(entry.displayName())
                : null;
        StepSummary summary = new StepSummary(
                AgentStepLabels.expertBefore(entry.displayName()),
                active,
                after);
        String stepId = speakStepId(entry);
        long eventAt = endedAt != null ? endedAt : startedAt;
        ProcessingStep step = new ProcessingStep(
                stepId,
                "expert",
                lifecycle,
                summary,
                startedAt,
                endedAt,
                durationMs,
                null,
                null,
                null,
                result,
                eventAt,
                AgentStepLabels.expertLabel(entry.displayName()),
                null,
                null,
                null);
        return StreamToken.step(step);
    }
}
