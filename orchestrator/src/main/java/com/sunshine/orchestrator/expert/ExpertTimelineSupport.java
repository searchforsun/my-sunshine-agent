package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.StepSummary;

import java.util.List;
import java.util.stream.Collectors;

public final class ExpertTimelineSupport {
    private ExpertTimelineSupport() {
    }

    public static String speakStepId(ExpertTranscriptEntry entry) {
        return "expert-" + entry.expertId() + "-s" + entry.speakSeq();
    }

    public static StreamToken conveneRunning(long startedAt) {
        StepSummary summary = new StepSummary(
                ExpertStepLabels.conveneBefore(),
                ExpertStepLabels.conveneActive(),
                null);
        ProcessingStep step = new ProcessingStep(
                "expert-convene",
                "expert-convene",
                "running",
                summary,
                startedAt, null, null,
                null, null, null, null,
                startedAt, ExpertStepLabels.conveneLabel(),
                null, null, null);
        return StreamToken.step(step);
    }

    public static StreamToken conveneDone(long startedAt, List<String> displayNames, String coordinatorReason) {
        long endedAt = System.currentTimeMillis();
        String names = displayNames.stream().collect(Collectors.joining("、"));
        String after = ExpertStepLabels.conveneAfter(names);
        StepSummary summary = new StepSummary(
                ExpertStepLabels.conveneBefore(),
                ExpertStepLabels.conveneActive(),
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
                endedAt, ExpertStepLabels.conveneLabel(),
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
                ExpertStepLabels.expertBefore(entry.displayName()),
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
                ExpertStepLabels.expertLabel(entry.displayName()),
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
                ? ExpertStepLabels.expertActiveResponding(entry.displayName())
                : ExpertStepLabels.expertActive(entry.displayName());
        String after = lifecycle.equals("done")
                ? ExpertStepLabels.expertAfter(entry.displayName())
                : null;
        StepSummary summary = new StepSummary(
                ExpertStepLabels.expertBefore(entry.displayName()),
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
                ExpertStepLabels.expertLabel(entry.displayName()),
                null,
                null,
                null);
        return StreamToken.step(step);
    }
}
