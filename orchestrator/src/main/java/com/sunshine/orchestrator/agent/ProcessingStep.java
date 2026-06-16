package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.StepSummary;

/**
 * 后端处理流水线步骤 — 通过 SSE type:step 推送给前端时间线（V3）
 */
public record ProcessingStep(
        String id,
        String phase,
        String lifecycle,
        StepSummary summary,
        Long startedAt,
        Long endedAt,
        Long durationMs,
        String detail,
        String reasoning,
        String output,
        String result,
        long ts,
        String status,
        String label
) {
    public static ProcessingStep running(String id, String phase, String label) {
        long ts = System.currentTimeMillis();
        return new ProcessingStep(
                id,
                phase,
                "running",
                new StepSummary(null, label, null),
                ts,
                null,
                null,
                null,
                null,
                null,
                null,
                ts,
                "running",
                label
        );
    }

    public static ProcessingStep done(String id, String phase, String label, String detail) {
        long ts = System.currentTimeMillis();
        String after = detail != null ? detail : label;
        return new ProcessingStep(
                id,
                phase,
                "done",
                new StepSummary(null, null, after),
                null,
                ts,
                null,
                detail,
                null,
                null,
                detail,
                ts,
                "done",
                label
        );
    }

    public static ProcessingStep error(String id, String phase, String label, String detail) {
        long ts = System.currentTimeMillis();
        return new ProcessingStep(
                id,
                phase,
                "error",
                new StepSummary(null, null, detail),
                null,
                ts,
                null,
                detail,
                null,
                null,
                detail,
                ts,
                "error",
                label
        );
    }
}
