package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.HitlStepMeta;
import com.sunshine.orchestrator.processing.SpawnSubagentLabels;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.StepSummary;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 子 Agent 步骤 → 挂到 subagent-{runId}.subSteps，主 Timeline 仅一张卡 */
public final class SpawnSubagentTimelineBridge {

    private final String parentStepId;
    private final String label;
    private final String spawnPrompt;
    private final SubStepsFold subSteps = new SubStepsFold();
    /** 用户取消后禁止再下发父卡 running，避免覆盖 paused */
    private final AtomicBoolean userCancelled = new AtomicBoolean(false);

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

    public void markUserCancelled() {
        userCancelled.set(true);
    }

    public boolean userCancelled() {
        return userCancelled.get();
    }

    public List<StreamToken> wrap(StreamToken token) {
        if (token == null || token.isReasoning()) {
            return List.of();
        }
        if (userCancelled.get()) {
            // 仍折叠 subSteps 供抽屉历史，但不再刷新父卡为 running
            subSteps.ingest(token);
            return List.of();
        }
        var routed = SubAgentContentTokens.route(token, parentStepId);
        if (routed.isPresent()) {
            return routed.get();
        }
        if (subSteps.ingest(token)) {
            return List.of(parentStepUpdate(runningLifecycle(), SpawnSubagentLabels.active(label), null, null));
        }
        return List.of();
    }

    /** 终态：done / error + result；保留 spawnPrompt */
    public List<StreamToken> complete(String after, String result, boolean ok) {
        // 用户已取消：禁止 done/error 覆盖 paused
        if (userCancelled.get()) {
            return List.of();
        }
        String lifecycle = ok ? "done" : "error";
        String afterLine = StringUtils.hasText(after)
                ? after.strip()
                : (ok ? SpawnSubagentLabels.after() : SpawnSubagentLabels.afterFail());
        return List.of(parentStepUpdate(lifecycle, null, afterLine, result));
    }

    /** 用户取消：lifecycle=paused（非 error），须下发终态 step SSE */
    public List<StreamToken> cancel(String after, String result) {
        userCancelled.set(true);
        String afterLine = StringUtils.hasText(after)
                ? after.strip()
                : SpawnSubagentLabels.afterCancel();
        return List.of(parentStepUpdate("paused", null, afterLine, result));
    }

    public List<ProcessingStep> subSteps() {
        return subSteps.snapshot();
    }

    private String runningLifecycle() {
        if (hasAwaitingHitl()) {
            return "paused";
        }
        return "running";
    }

    private boolean hasAwaitingHitl() {
        for (ProcessingStep step : subSteps.view()) {
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
                isTerminalLifecycle(lifecycle, after) ? ts : null,
                null,
                null,
                null,
                null,
                result,
                ts,
                label,
                metadata,
                null,
                subSteps.snapshot());
        return StreamToken.step(parent);
    }

    /** done/error 终态；paused + after 表示用户取消（HITL 中途 paused 无 after，不算终态） */
    private static boolean isTerminalLifecycle(String lifecycle, String after) {
        if ("done".equals(lifecycle) || "error".equals(lifecycle)) {
            return true;
        }
        return "paused".equals(lifecycle) && StringUtils.hasText(after);
    }
}
