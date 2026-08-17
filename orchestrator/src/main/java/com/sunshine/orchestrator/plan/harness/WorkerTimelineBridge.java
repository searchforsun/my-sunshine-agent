package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.SubStepsFold;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.StepSummary;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Worker 执行行 → 主时间线一级 {@code worker-{taskId}} 卡 + subSteps。
 * <p>
 * 与 {@code SpawnSubagentTimelineBridge} 同构：Planner 直接经 {@code dispatch_worker} 调度 Worker 时，
 * Worker 内部 think/tool 步骤折叠为该一级行的 subSteps；不再依赖 Loop 兜底才出现 worker-* 骨架。
 */
public final class WorkerTimelineBridge {

    private final String parentStepId;
    private final String label;
    private final SubStepsFold subSteps = new SubStepsFold();

    public WorkerTimelineBridge(String taskId, String label) {
        this.parentStepId = parentStepId(taskId);
        this.label = StringUtils.hasText(label) ? label.strip() : "Worker";
    }

    public static String parentStepId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return "worker-unknown";
        }
        String id = taskId.strip();
        return id.startsWith("worker-") ? id : "worker-" + id;
    }

    public String parentStepId() {
        return parentStepId;
    }

    public String label() {
        return label;
    }

    /** 执行前 running 骨架（一级行占位） */
    public List<StreamToken> begin() {
        return List.of(parentStepUpdate("running", activeLabel(), null, null));
    }

    /** Worker 内部步骤 → subSteps；reasoning/content 忽略（正文由 complete 收束为 result）。 */
    public List<StreamToken> wrap(StreamToken token) {
        if (token == null || token.isReasoning()) {
            return List.of();
        }
        if (subSteps.ingest(token)) {
            return List.of(parentStepUpdate("running", activeLabel(), null, null));
        }
        return List.of();
    }

    public List<StreamToken> complete(String after, String result, boolean ok) {
        String lifecycle = ok ? "done" : "error";
        String afterLine = StringUtils.hasText(after) ? after.strip() : (ok ? "完成" : "执行失败");
        return List.of(parentStepUpdate(lifecycle, null, afterLine, result));
    }

    public List<ProcessingStep> subSteps() {
        return subSteps.snapshot();
    }

    private String activeLabel() {
        return StringUtils.hasText(label) ? label + " 执行中" : "Worker 执行中";
    }

    private StreamToken parentStepUpdate(String lifecycle, String active, String after, String result) {
        long ts = System.currentTimeMillis();
        StepSummary summary = new StepSummary(null, active, after);
        ProcessingStep parent = new ProcessingStep(
                parentStepId,
                "worker",
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
                null,
                null,
                subSteps.snapshot(),
                null);
        return StreamToken.step(parent);
    }
}
