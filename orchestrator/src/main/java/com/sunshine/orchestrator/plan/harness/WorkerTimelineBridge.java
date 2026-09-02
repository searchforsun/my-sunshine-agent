package com.sunshine.orchestrator.plan.harness;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.SubAgentContentTokens;
import com.sunshine.orchestrator.agent.SubStepsFold;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.StepMetadata;
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

    /**
     * 父步快照节流间隔：Worker 内部 think reasoning / tool output 等 step_delta 增量逐 token 到达，
     * 若每次增量都下发一次「含完整 subSteps」的父步快照，SSE 事件量将膨胀到每字符一次（实测单个
     * Worker 产生上千个冗余 step 事件），前端时间线重渲染被拖垮。故内容增量按本间隔合并下发；
     * 结构变化（step 事件）与终态快照（complete）不受节流，保证流式体验与最终一致性。
     */
    private static final long SNAPSHOT_THROTTLE_MS = 200L;

    private final String parentStepId;
    private final String label;
    /** Worker 任务契约（taskGoal/constraints/expectedOutput/successCriteria），抽屉「传入提示词」展示 */
    private final String taskContract;
    /** 异步 worker runId：metadata.workerRunId 下发，前端可单独取消本 worker 卡 */
    private final String runId;
    private final SubStepsFold subSteps = new SubStepsFold();
    private volatile long lastSnapshotAt;

    public WorkerTimelineBridge(String taskId, String label, String taskContract, String runId) {
        this.parentStepId = parentStepId(taskId);
        this.label = StringUtils.hasText(label) ? label.strip() : "Worker";
        this.taskContract = taskContract != null ? taskContract : "";
        this.runId = runId != null ? runId : "";
    }

    public WorkerTimelineBridge(String taskId, String label, String taskContract) {
        this(taskId, label, taskContract, "");
    }

    public WorkerTimelineBridge(String taskId, String label) {
        this(taskId, label, "");
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
        long now = System.currentTimeMillis();
        lastSnapshotAt = now;
        return List.of(parentStepUpdate("running", null, null, now));
    }

    /**
     * Worker 内部步骤 → subSteps；content 路由到父卡。
     * v17.17：reasoning step_delta 也折叠进 subSteps（前端 running 卡展示当前子步思考正文，
     * 此前丢弃导致 think 子步无 reasoning，卡片只能回退「深度思考」阶段标题）。
     */
    public List<StreamToken> wrap(StreamToken token) {
        if (token == null) {
            return List.of();
        }
        var routed = SubAgentContentTokens.route(token, parentStepId);
        if (routed.isPresent()) {
            return routed.get();
        }
        if (subSteps.ingest(token)) {
            return snapshotIfDue(token);
        }
        return List.of();
    }

    /**
     * 快照时机：结构变化（step）立即下发；内容增量（step_delta）按节流合并，
     * 避免逐 token 全量下发把 SSE 打爆（见 {@link #SNAPSHOT_THROTTLE_MS}）。
     */
    private List<StreamToken> snapshotIfDue(StreamToken token) {
        long now = System.currentTimeMillis();
        if (token.isStep() || now - lastSnapshotAt >= SNAPSHOT_THROTTLE_MS) {
            lastSnapshotAt = now;
            return List.of(parentStepUpdate("running", null, null, null));
        }
        return List.of();
    }

    /**
     * 收束：worker 主时间线一级行落定终态。
     * Worker 正文已在 run 期间经 {@link #wrap} 的 content 路由流式下发到父步 result；
     * 终态把终稿 result 透传给前端作为兜底（节流期间未下发的尾部增量由此覆盖），
     * 不再追加「任务结果汇总」handoff 子步，与 spawn subagent 正文流式同构。
     */
    public List<StreamToken> complete(String result, boolean ok) {
        String lifecycle = ok ? "done" : "error";
        return List.of(parentStepUpdate(lifecycle, null, result, null));
    }

    /**
     * 用户取消终态：lifecycle=paused + summary.after=已取消（与 spawn subagent 取消同构）。
     * TaskBoard 侧由 TaskItem.status=cancelled 呈现 ⊗。
     */
    public List<StreamToken> cancel(String result) {
        long ts = System.currentTimeMillis();
        StepSummary summary = new StepSummary(null, null, "已取消");
        ProcessingStep parent = new ProcessingStep(
                parentStepId,
                "worker",
                "paused",
                summary,
                null,
                ts,
                null,
                null,
                null,
                null,
                result,
                ts,
                label,
                buildMetadata(),
                null,
                subSteps.snapshot(),
                null);
        return List.of(StreamToken.step(parent));
    }

    public List<ProcessingStep> subSteps() {
        return subSteps.snapshot();
    }

    private StreamToken parentStepUpdate(String lifecycle, String after, String result, Long startedAt) {
        long ts = System.currentTimeMillis();
        StepSummary summary = new StepSummary(null, null, after);
        ProcessingStep parent = new ProcessingStep(
                parentStepId,
                "worker",
                lifecycle,
                summary,
                startedAt,
                "done".equals(lifecycle) || "error".equals(lifecycle) ? ts : null,
                null,
                null,
                null,
                null,
                result,
                ts,
                label,
                buildMetadata(),
                null,
                subSteps.snapshot(),
                null);
        return StreamToken.step(parent);
    }

    /** worker 卡元数据：spawnPrompt（任务契约） + workerRunId（单独取消） */
    private StepMetadata buildMetadata() {
        StepMetadata meta = StringUtils.hasText(taskContract)
                ? StepMetadata.withSpawnPrompt(null, taskContract)
                : null;
        if (StringUtils.hasText(runId)) {
            meta = StepMetadata.withWorkerRunId(meta, runId);
        }
        return meta;
    }
}
