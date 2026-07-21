package com.sunshine.orchestrator.processing;

import com.sunshine.common.sandbox.SandboxEditDiff;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/** StepMetadata 工厂与增量合并 — 路由/HITL/recovery/planApproval 等域 */
final class StepMetadataAssembler {

    private static final String RAG_EXPAND_SECTION_TITLE = "检索过程";

    private StepMetadataAssembler() {
    }

    static StepMetadata fromSandbox(String sandboxPath, String sandboxSearchRoot) {
        if (!StringUtils.hasText(sandboxPath) && !StringUtils.hasText(sandboxSearchRoot)) {
            return null;
        }
        return new StepMetadata(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null,
                textOrNull(sandboxPath), textOrNull(sandboxSearchRoot), null, null, null);
    }

    static StepMetadata withEditDiff(StepMetadata base, SandboxEditDiff editDiff) {
        if (editDiff == null) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, editDiff);
        }
        return new StepMetadata(
                base.hitCount(), base.sources(), base.rewriteApplied(), base.rewriteLatencyMs(),
                base.rewriteFrom(), base.rewriteTo(), base.rewriteScenario(), base.rewriteScenarioLabel(),
                base.skillId(), base.plannerMode(), base.routingReason(), base.rewriteInDetail(),
                base.expandSectionTitle(), base.hitl(), base.recovery(), base.nodeAttempts(),
                base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress(),
                base.sandboxPath(), base.sandboxSearchRoot(), base.spawnPrompt(), base.cancellable(),
                editDiff);
    }

    static StepMetadata withSpawnPrompt(StepMetadata base, String prompt) {
        String p = textOrNull(prompt);
        if (p == null) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, p, null, null);
        }
        return new StepMetadata(
                base.hitCount(), base.sources(), base.rewriteApplied(), base.rewriteLatencyMs(),
                base.rewriteFrom(), base.rewriteTo(), base.rewriteScenario(), base.rewriteScenarioLabel(),
                base.skillId(), base.plannerMode(), base.routingReason(), base.rewriteInDetail(),
                base.expandSectionTitle(), base.hitl(), base.recovery(), base.nodeAttempts(),
                base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress(),
                base.sandboxPath(), base.sandboxSearchRoot(), p, base.cancellable(), base.editDiff());
    }

    static StepMetadata withCancellable(StepMetadata base, boolean cancellable) {
        Boolean flag = cancellable ? Boolean.TRUE : Boolean.FALSE;
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, flag, null);
        }
        return new StepMetadata(
                base.hitCount(), base.sources(), base.rewriteApplied(), base.rewriteLatencyMs(),
                base.rewriteFrom(), base.rewriteTo(), base.rewriteScenario(), base.rewriteScenarioLabel(),
                base.skillId(), base.plannerMode(), base.routingReason(), base.rewriteInDetail(),
                base.expandSectionTitle(), base.hitl(), base.recovery(), base.nodeAttempts(),
                base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress(),
                base.sandboxPath(), base.sandboxSearchRoot(), base.spawnPrompt(), flag, base.editDiff());
    }

    static StepMetadata fromRouting(ExecutionPlan plan) {
        if (plan == null) {
            return null;
        }
        Map<String, String> params = plan.params() != null ? plan.params() : Map.of();
        String skill = params.get(SkillBindingOutcome.PARAM_SKILL);
        String mode = params.get(SkillBindingOutcome.PARAM_PLANNER_MODE);
        String reason = plan.reason();
        if (!StringUtils.hasText(skill) && !StringUtils.hasText(mode) && !StringUtils.hasText(reason)) {
            return null;
        }
        return new StepMetadata(
                null, null, null, null, null, null, null, null,
                textOrNull(skill), textOrNull(mode), textOrNull(reason), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    static StepMetadata fromSkillLoad(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return null;
        }
        return new StepMetadata(
                null, null, null, null, null, null, null, null,
                skillId.strip(), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    static StepMetadata withTasks(List<TaskBoardItemView> tasks, Integer revision, String progress) {
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        return new StepMetadata(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.copyOf(tasks), revision, progress, null, null, null, null, null);
    }

    static StepMetadata mergeRouting(StepMetadata base, ExecutionPlan plan) {
        StepMetadata routing = fromRouting(plan);
        if (routing == null) {
            return base;
        }
        if (base == null) {
            return routing;
        }
        return copy(base, routing.skillId(), routing.plannerMode(), routing.routingReason(),
                base.rewriteInDetail(), base.expandSectionTitle(), base.hitl(), base.recovery(),
                base.nodeAttempts(), base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress());
    }

    static StepMetadata withHitl(StepMetadata base, HitlStepMeta hitl) {
        if (hitl == null) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, hitl, null, null, null,
                    null, null, null, null, null, null, null, null);
        }
        return copy(base, base.skillId(), base.plannerMode(), base.routingReason(),
                base.rewriteInDetail(), base.expandSectionTitle(), hitl, base.recovery(),
                base.nodeAttempts(), base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress());
    }

    static StepMetadata withoutRecovery(StepMetadata base) {
        if (base == null || base.recovery() == null) {
            return base;
        }
        return copy(base, base.skillId(), base.plannerMode(), base.routingReason(),
                base.rewriteInDetail(), base.expandSectionTitle(), base.hitl(), null,
                base.nodeAttempts(), base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress());
    }

    static StepMetadata withNodeAttempts(StepMetadata base, List<NodeAttemptMeta> nodeAttempts) {
        if (nodeAttempts == null || nodeAttempts.isEmpty()) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, List.copyOf(nodeAttempts), null,
                    null, null, null, null, null, null, null, null);
        }
        return copy(base, base.skillId(), base.plannerMode(), base.routingReason(),
                base.rewriteInDetail(), base.expandSectionTitle(), base.hitl(), base.recovery(),
                List.copyOf(nodeAttempts), base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress());
    }

    static StepMetadata withRecovery(StepMetadata base, NodeRecoveryMeta recovery) {
        if (recovery == null) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, recovery, null, null,
                    null, null, null, null, null, null, null, null);
        }
        return copy(base, base.skillId(), base.plannerMode(), base.routingReason(),
                base.rewriteInDetail(), base.expandSectionTitle(), base.hitl(), recovery,
                base.nodeAttempts(), base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress());
    }

    static StepMetadata fromRewrite(QueryRewriteOutcome outcome) {
        if (outcome == null || !outcome.applied()) {
            return null;
        }
        String scenarioLabel = outcome.resolveScenarioLabel();
        return new StepMetadata(
                null,
                null,
                true,
                outcome.latencyMs(),
                outcome.originalQuery(),
                outcome.rewrittenQuery(),
                outcome.scenario(),
                scenarioLabel.isBlank() ? null : scenarioLabel,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    static StepMetadata withPlanApproval(StepMetadata base, PlanApprovalMeta planApproval) {
        if (planApproval == null) {
            return base;
        }
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, planApproval,
                    null, null, null, null, null, null, null, null);
        }
        return copy(base, base.skillId(), base.plannerMode(), base.routingReason(),
                base.rewriteInDetail(), base.expandSectionTitle(), base.hitl(), base.recovery(),
                base.nodeAttempts(), planApproval, base.tasks(), base.taskRevision(), base.taskProgress());
    }

    static StepMetadata mergeRewrite(StepMetadata base, QueryRewriteOutcome outcome) {
        StepMetadata rewriteMeta = fromRewrite(outcome);
        if (rewriteMeta == null) {
            return base;
        }
        if (base == null) {
            return rewriteMeta;
        }
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                rewriteMeta.rewriteApplied(),
                rewriteMeta.rewriteLatencyMs(),
                rewriteMeta.rewriteFrom(),
                rewriteMeta.rewriteTo(),
                rewriteMeta.rewriteScenario(),
                rewriteMeta.rewriteScenarioLabel(),
                base.skillId(),
                base.plannerMode(),
                base.routingReason(),
                base.rewriteInDetail(),
                base.expandSectionTitle(),
                base.hitl(),
                base.recovery(),
                base.nodeAttempts(),
                base.planApproval(),
                base.tasks(),
                base.taskRevision(),
                base.taskProgress(),
                base.sandboxPath(),
                base.sandboxSearchRoot(),
                base.spawnPrompt(),
                base.cancellable(),
                base.editDiff());
    }

    static StepMetadata merge(StepMetadata base, StepMetadata overlay) {
        if (overlay == null) {
            return base;
        }
        if (base == null) {
            return overlay;
        }
        return new StepMetadata(
                overlay.hitCount() != null ? overlay.hitCount() : base.hitCount(),
                overlay.sources() != null && !overlay.sources().isEmpty() ? overlay.sources() : base.sources(),
                overlay.rewriteApplied() != null ? overlay.rewriteApplied() : base.rewriteApplied(),
                overlay.rewriteLatencyMs() != null ? overlay.rewriteLatencyMs() : base.rewriteLatencyMs(),
                overlay.rewriteFrom() != null ? overlay.rewriteFrom() : base.rewriteFrom(),
                overlay.rewriteTo() != null ? overlay.rewriteTo() : base.rewriteTo(),
                overlay.rewriteScenario() != null ? overlay.rewriteScenario() : base.rewriteScenario(),
                overlay.rewriteScenarioLabel() != null ? overlay.rewriteScenarioLabel() : base.rewriteScenarioLabel(),
                overlay.skillId() != null ? overlay.skillId() : base.skillId(),
                overlay.plannerMode() != null ? overlay.plannerMode() : base.plannerMode(),
                overlay.routingReason() != null ? overlay.routingReason() : base.routingReason(),
                overlay.rewriteInDetail() != null ? overlay.rewriteInDetail() : base.rewriteInDetail(),
                overlay.expandSectionTitle() != null ? overlay.expandSectionTitle() : base.expandSectionTitle(),
                overlay.hitl() != null ? overlay.hitl() : base.hitl(),
                overlay.recovery() != null ? overlay.recovery() : base.recovery(),
                overlay.nodeAttempts() != null && !overlay.nodeAttempts().isEmpty()
                        ? overlay.nodeAttempts() : base.nodeAttempts(),
                overlay.planApproval() != null ? overlay.planApproval() : base.planApproval(),
                overlay.tasks() != null && !overlay.tasks().isEmpty() ? overlay.tasks() : base.tasks(),
                overlay.taskRevision() != null ? overlay.taskRevision() : base.taskRevision(),
                overlay.taskProgress() != null ? overlay.taskProgress() : base.taskProgress(),
                overlay.sandboxPath() != null ? overlay.sandboxPath() : base.sandboxPath(),
                overlay.sandboxSearchRoot() != null ? overlay.sandboxSearchRoot() : base.sandboxSearchRoot(),
                overlay.spawnPrompt() != null ? overlay.spawnPrompt() : base.spawnPrompt(),
                overlay.cancellable() != null ? overlay.cancellable() : base.cancellable(),
                overlay.editDiff() != null ? overlay.editDiff() : base.editDiff());
    }

    static StepMetadata withRewriteInDetail(StepMetadata base) {
        if (base == null) {
            return null;
        }
        return copy(base, base.skillId(), base.plannerMode(), base.routingReason(),
                true, base.expandSectionTitle(), base.hitl(), base.recovery(),
                base.nodeAttempts(), base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress());
    }

    static StepMetadata withRagExpandLayout(StepMetadata base) {
        if (base == null) {
            return new StepMetadata(null, null, null, null, null, null, null, null,
                    null, null, null, true, RAG_EXPAND_SECTION_TITLE, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }
        return copy(base, base.skillId(), base.plannerMode(), base.routingReason(),
                true, RAG_EXPAND_SECTION_TITLE, base.hitl(), base.recovery(),
                base.nodeAttempts(), base.planApproval(), base.tasks(), base.taskRevision(), base.taskProgress());
    }

    private static StepMetadata copy(
            StepMetadata base,
            String skillId,
            String plannerMode,
            String routingReason,
            Boolean rewriteInDetail,
            String expandSectionTitle,
            HitlStepMeta hitl,
            NodeRecoveryMeta recovery,
            List<NodeAttemptMeta> nodeAttempts,
            PlanApprovalMeta planApproval,
            List<TaskBoardItemView> tasks,
            Integer taskRevision,
            String taskProgress) {
        return new StepMetadata(
                base.hitCount(),
                base.sources(),
                base.rewriteApplied(),
                base.rewriteLatencyMs(),
                base.rewriteFrom(),
                base.rewriteTo(),
                base.rewriteScenario(),
                base.rewriteScenarioLabel(),
                skillId,
                plannerMode,
                routingReason,
                rewriteInDetail,
                expandSectionTitle,
                hitl,
                recovery,
                nodeAttempts,
                planApproval,
                tasks,
                taskRevision,
                taskProgress,
                base.sandboxPath(),
                base.sandboxSearchRoot(),
                base.spawnPrompt(),
                base.cancellable(),
                base.editDiff());
    }

    private static String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
