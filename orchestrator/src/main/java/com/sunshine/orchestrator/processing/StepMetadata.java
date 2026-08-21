package com.sunshine.orchestrator.processing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.RoutingTrace;
import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import org.springframework.util.StringUtils;

import java.util.List;

/** 时间线步骤结构化元数据（如 RAG 命中数与来源文档、QueryRewrite 可观测） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StepMetadata(
        Integer hitCount,
        List<String> sources,
        Boolean rewriteApplied,
        Long rewriteLatencyMs,
        String rewriteFrom,
        String rewriteTo,
        String rewriteScenario,
        String rewriteScenarioLabel,
        /** L0 Skill 绑定：intent 步可观测 */
        String skillId,
        String plannerMode,
        String routingReason,
        /** RAG 等：改写链路已在 detail 正文，前端勿再渲染 metadata 结构化改写区 */
        Boolean rewriteInDetail,
        /** 抽屉/展开区 detail 区块标题（如「检索过程」） */
        String expandSectionTitle,
        /** 写工具 HITL 确认态（awaiting 时前端展示内联按钮） */
        HitlStepMeta hitl,
        /** Workflow 节点失败：用户重试/终止 */
        NodeRecoveryMeta recovery,
        /** Workflow 节点执行 attempt 列表（重试过程实时下发） */
        List<NodeAttemptMeta> nodeAttempts,
        /** ReAct TaskBoard 清单项 */
        List<TaskBoardItemView> tasks,
        Integer taskRevision,
        String taskProgress,
        /** 沙箱 read/write/edit：容器内完整路径（主行 after 可能为 fileName） */
        String sandboxPath,
        /** 沙箱 glob：搜索根（主行 after 为 pattern · root） */
        String sandboxSearchRoot,
        /** ReAct spawn_subagent：传入子 Agent 的 prompt（抽屉展示） */
        String spawnPrompt,
        /** 沙箱可单工具取消：UI 跟此字段，勿硬编码工具名单 */
        Boolean cancellable,
        /** 沙箱 edit：Git contextual diff（绝对行号）；UI 只认此字段 */
        SandboxEditDiff editDiff,
        /** ReAct request_decision：选择题载荷（勿截断 question/options） */
        DecisionStepMeta decision,
        /** 路由链路可观测：模式 → 轨 → L0 → 规则 → L3 → 最终绑定（intent 步抽屉） */
        List<RoutingTrace> routingTraces,
        /** Planner-Executor worker：异步 runId（前端单独取消 worker 卡） */
        String workerRunId,
        /** harness H1 taskQueue 投影（执行单元 versionedId，如 t1-1/t1-2；与 tasks 同形，前端优先展示并加 T1-1 记号） */
        List<TaskBoardItemView> taskQueue
) {

    public static StepMetadata withTasks(List<TaskBoardItemView> tasks, Integer revision, String progress) {
        return StepMetadataAssembler.withTasks(tasks, revision, progress);
    }

    public static StepMetadata withTaskQueue(List<TaskBoardItemView> tasks, Integer revision, String progress) {
        return StepMetadataAssembler.withTaskQueue(tasks, revision, progress);
    }

    public static StepMetadata fromRagToolOutput(String text) {
        return RagStepMetadataParser.fromRagToolOutput(text);
    }

    public static StepMetadata fromRagToolOutput(String rawText, String summarizedText) {
        return RagStepMetadataParser.fromRagToolOutput(rawText, summarizedText);
    }

    public static StepMetadata fromRouting(ExecutionPlan plan) {
        return StepMetadataAssembler.fromRouting(plan);
    }

    public static StepMetadata fromSkillLoad(String skillId) {
        return StepMetadataAssembler.fromSkillLoad(skillId);
    }

    public static StepMetadata mergeRouting(StepMetadata base, ExecutionPlan plan) {
        return StepMetadataAssembler.mergeRouting(base, plan);
    }

    public static StepMetadata withHitl(StepMetadata base, HitlStepMeta hitl) {
        return StepMetadataAssembler.withHitl(base, hitl);
    }

    public static StepMetadata withoutRecovery(StepMetadata base) {
        return StepMetadataAssembler.withoutRecovery(base);
    }

    public static StepMetadata withNodeAttempts(StepMetadata base, List<NodeAttemptMeta> nodeAttempts) {
        return StepMetadataAssembler.withNodeAttempts(base, nodeAttempts);
    }

    public static StepMetadata withRecovery(StepMetadata base, NodeRecoveryMeta recovery) {
        return StepMetadataAssembler.withRecovery(base, recovery);
    }

    public static StepMetadata fromRewrite(QueryRewriteOutcome outcome) {
        return StepMetadataAssembler.fromRewrite(outcome);
    }

    public static StepMetadata withDecision(StepMetadata base, DecisionStepMeta decision) {
        return StepMetadataAssembler.withDecision(base, decision);
    }

    public static StepMetadata mergeRewrite(StepMetadata base, QueryRewriteOutcome outcome) {
        return StepMetadataAssembler.mergeRewrite(base, outcome);
    }

    public static StepMetadata merge(StepMetadata base, StepMetadata overlay) {
        return StepMetadataAssembler.merge(base, overlay);
    }

    public static StepMetadata withRewriteInDetail(StepMetadata base) {
        return StepMetadataAssembler.withRewriteInDetail(base);
    }

    public static StepMetadata withRagExpandLayout(StepMetadata base) {
        return StepMetadataAssembler.withRagExpandLayout(base);
    }

    public static StepMetadata fromSandbox(String sandboxPath, String sandboxSearchRoot) {
        return StepMetadataAssembler.fromSandbox(sandboxPath, sandboxSearchRoot);
    }

    public static StepMetadata withSpawnPrompt(StepMetadata base, String prompt) {
        return StepMetadataAssembler.withSpawnPrompt(base, prompt);
    }

    public static StepMetadata withCancellable(StepMetadata base, boolean cancellable) {
        return StepMetadataAssembler.withCancellable(base, cancellable);
    }

    public static StepMetadata withEditDiff(StepMetadata base, SandboxEditDiff editDiff) {
        return StepMetadataAssembler.withEditDiff(base, editDiff);
    }

    public static StepMetadata withWorkerRunId(StepMetadata base, String runId) {
        return StepMetadataAssembler.withWorkerRunId(base, runId);
    }

    @JsonIgnore
    public String sourcesLabel() {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return String.join("、", sources);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return (hitCount == null || hitCount == 0)
                && (sources == null || sources.isEmpty())
                && (rewriteApplied == null || !rewriteApplied)
                && !StringUtils.hasText(skillId)
                && !StringUtils.hasText(plannerMode)
                && !StringUtils.hasText(routingReason)
                && hitl == null
                && recovery == null
                && (nodeAttempts == null || nodeAttempts.isEmpty())
                && (tasks == null || tasks.isEmpty())
                && taskRevision == null
                && !StringUtils.hasText(taskProgress)
                && (taskQueue == null || taskQueue.isEmpty())
                && !StringUtils.hasText(sandboxPath)
                && !StringUtils.hasText(sandboxSearchRoot)
                && !StringUtils.hasText(spawnPrompt)
                && (cancellable == null || !cancellable)
                && editDiff == null
                && decision == null
                && (routingTraces == null || routingTraces.isEmpty())
                && !StringUtils.hasText(workerRunId);
    }
}
