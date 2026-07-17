package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.HitlStepMeta;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 子 Agent 步骤 → 挂到 subagent-{runId}.subSteps，主 Timeline 仅一张卡 */
public final class SpawnSubagentTimelineBridge {

    private final String parentStepId;
    private final String label;
    private final String spawnPrompt;
    private final List<ProcessingStep> subSteps = new ArrayList<>();

    public SpawnSubagentTimelineBridge(String runId, String label, String spawnPrompt) {
        this.parentStepId = parentStepId(runId);
        this.label = StringUtils.hasText(label) ? label.strip() : SpawnSubagentLabels.label();
        this.spawnPrompt = spawnPrompt;
    }

    public static String parentStepId(String runId) {
        if (!StringUtils.hasText(runId)) {
            return "subagent-unknown";
        }
        String id = runId.strip();
        return id.startsWith("subagent-") ? id : "subagent-" + id;
    }

    public String parentStepId() {
        return parentStepId;
    }

    public String label() {
        return label;
    }

    public String spawnPrompt() {
        return spawnPrompt;
    }

    public List<StreamToken> wrap(StreamToken token) {
        if (token == null) {
            return List.of();
        }
        if (token.isStep() && token.step() != null) {
            ProcessingStepMerger.upsert(subSteps, token.step());
            return List.of(parentStepUpdate(runningLifecycle(), SpawnSubagentLabels.active(label), null, null));
        }
        if (token.isStepDelta()) {
            ProcessingStepMerger.applyDelta(subSteps, token.stepId(), token.channel(), token.text());
            return List.of(parentStepUpdate(runningLifecycle(), SpawnSubagentLabels.active(label), null, null));
        }
        return List.of();
    }

    /** 终态：done / error + result；保留 spawnPrompt */
    public List<StreamToken> complete(String after, String result, boolean ok) {
        String lifecycle = ok ? "done" : "error";
        String afterLine = StringUtils.hasText(after)
                ? after.strip()
                : (ok ? SpawnSubagentLabels.after() : SpawnSubagentLabels.afterFail());
        return List.of(parentStepUpdate(lifecycle, null, afterLine, result));
    }

    public List<ProcessingStep> subSteps() {
        return List.copyOf(subSteps);
    }

    private String runningLifecycle() {
        if (hasAwaitingHitl()) {
            return "paused";
        }
        return "running";
    }

    private boolean hasAwaitingHitl() {
        for (ProcessingStep step : subSteps) {
            if (step.metadata() != null
                    && step.metadata().hitl() != null
                    && HitlStepMeta.STATUS_AWAITING.equals(step.metadata().hitl().status())) {
                return true;
            }
            if (step.subSteps() != null) {
                for (ProcessingStep nested : step.subSteps()) {
                    if (nested.metadata() != null
                            && nested.metadata().hitl() != null
                            && HitlStepMeta.STATUS_AWAITING.equals(nested.metadata().hitl().status())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private StreamToken parentStepUpdate(String lifecycle, String active, String after, String result) {
        long ts = System.currentTimeMillis();
        String before = SpawnSubagentLabels.before();
        StepSummary summary = new StepSummary(before, active, after);
        StepMetadata metadata = StepMetadata.withSpawnPrompt(null, spawnPrompt);
        ProcessingStep parent = new ProcessingStep(
                parentStepId,
                "subagent",
                lifecycle,
                summary,
                null,
                "done".equals(lifecycle) || "error".equals(lifecycle) ? ts : null,
                null,
                null,
                null,
                null,
                result,
                ts,
                label,
                metadata,
                null,
                List.copyOf(subSteps));
        return StreamToken.step(parent);
    }
}
