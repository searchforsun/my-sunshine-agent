package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import org.springframework.util.StringUtils;

import java.util.List;

/** 时间线步骤结构化元数据（如 RAG 命中数与来源文档、QueryRewrite 可观测） */
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
        /** 动态 Plan 用户确认轮次 */
        PlanApprovalMeta planApproval,
        /** ReAct TaskBoard 清单项 */
        List<TaskBoardItemView> tasks,
        Integer taskRevision,
        String taskProgress
) {

    public static StepMetadata withTasks(List<TaskBoardItemView> tasks, Integer revision, String progress) {
        return StepMetadataAssembler.withTasks(tasks, revision, progress);
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

    public static StepMetadata withPlanApproval(StepMetadata base, PlanApprovalMeta planApproval) {
        return StepMetadataAssembler.withPlanApproval(base, planApproval);
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

    public String sourcesLabel() {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        return String.join("、", sources);
    }

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
                && planApproval == null
                && (tasks == null || tasks.isEmpty())
                && taskRevision == null
                && !StringUtils.hasText(taskProgress);
    }
}
