package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sunshine.orchestrator.processing.ContentBlock;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;

/**
 * 后端处理流水线步骤 — 通过 SSE type:step 推送给前端时间线（V3）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        String label,
        StepMetadata metadata,
        /** ReAct / 子 Agent 正文分段（抽屉 OperationStack 穿插） */
        java.util.List<ContentBlock> contentBlocks,
        /** Workflow agent 节点：子 Agent 完整 ReAct 步骤（仅抽屉展示，不上主 Timeline 顶层） */
        java.util.List<ProcessingStep> subSteps
) {
    public ProcessingStep {
        contentBlocks = contentBlocks != null && !contentBlocks.isEmpty()
                ? java.util.List.copyOf(contentBlocks) : null;
        subSteps = subSteps != null && !subSteps.isEmpty() ? java.util.List.copyOf(subSteps) : null;
    }
    public static ProcessingStep running(String id, String phase, String label) {
        long ts = System.currentTimeMillis();
        return new ProcessingStep(
                id,
                phase,
                "running",
                null,
                ts,
                null,
                null,
                null,
                null,
                null,
                null,
                ts,
                label,
                null,
                null,
                null
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
                label,
                null,
                null,
                null
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
                label,
                null,
                null,
                null
        );
    }
}
