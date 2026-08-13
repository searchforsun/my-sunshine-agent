package com.sunshine.orchestrator.routing;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.UnifiedRuleRoutingPolicy;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 用户强制 executionPreference：锁死 {@link ExecutionMode}，仍经 L0→同 mode 规则→L3 解析绑定
 * （skillId / reactPromptId / workflowId），不得改 mode。
 */
@Component
@RequiredArgsConstructor
public class ForcedExecutionRouter {

    private static final String REASON_REACT = "user:forced-react";
    private static final String REASON_PLAN = "user:forced-plan-workflow";
    private static final String REASON_WORKFLOW = "user:forced-workflow";
    private static final String PARAM_REACT_PROMPT = "reactPromptId";

    private final SkillBindingRoutingPolicy skillBindingRoutingPolicy;
    private final PromptCatalogHolder promptCatalogHolder;
    private final IntentRouter intentRouter;

    public Mono<ExecutionPlan> resolve(RoutingContext ctx, ExecutionPreference preference, String workflowId) {
        if (preference == null) {
            return Mono.error(new IllegalStateException("ForcedExecutionRouter 需要非空 preference"));
        }
        return switch (preference) {
            case FAST -> resolveForced(ctx, ExecutionMode.FAST, REASON_REACT, null);
            case PRO -> resolveForced(ctx, ExecutionMode.PRO, REASON_PLAN, null);
            case WORKFLOW -> resolveForced(ctx, ExecutionMode.WORKFLOW, REASON_WORKFLOW, workflowId);
        };
    }

    private Mono<ExecutionPlan> resolveForced(
            RoutingContext ctx, ExecutionMode locked, String reason, String explicitWorkflowId) {
        if (locked == ExecutionMode.WORKFLOW && StringUtils.hasText(explicitWorkflowId)) {
            return Mono.just(new ExecutionPlan(
                    ExecutionMode.WORKFLOW, explicitWorkflowId.strip(), Map.of(), reason));
        }
        BindingAcc acc = new BindingAcc(locked, reason);
        Mono<BindingAcc> pipeline = Mono.just(acc);
        if (ctx.allowsSkillBinding()) {
            pipeline = pipeline.flatMap(a -> skillBindingRoutingPolicy.tryRoute(ctx).map(opt -> {
                opt.ifPresent(skillPlan -> a.mergeParamsFillGaps(skillPlan.params()));
                return a;
            }));
        }
        return pipeline.flatMap(a -> {
            applySameModeRule(ctx.userMessage(), a);
            if (!a.needsL3()) {
                return Mono.just(a.toPlan());
            }
            return intentRouter.classifyPlan(ctx.withLockedMode(locked))
                    .flatMap(llm -> {
                        a.mergeFromL3(llm);
                        if (locked == ExecutionMode.WORKFLOW && !StringUtils.hasText(a.workflowId)) {
                            return Mono.error(new BizException(OrchestratorErrorCode.WORKFLOW_TEMPLATE_NOT_FOUND));
                        }
                        return Mono.just(a.toPlan());
                    });
        });
    }

    private void applySameModeRule(String userMessage, BindingAcc acc) {
        Optional<ExecutionPlan> hit = UnifiedRuleRoutingPolicy
                .match(promptCatalogHolder.snapshot().routingRules(), userMessage)
                .filter(p -> p.mode() == acc.locked);
        if (hit.isEmpty()) {
            return;
        }
        ExecutionPlan plan = hit.get();
        acc.sameModeRuleHit = true;
        if (!StringUtils.hasText(acc.workflowId) && StringUtils.hasText(plan.workflowId())) {
            acc.workflowId = plan.workflowId().strip();
        }
        acc.mergeParamsFillGaps(plan.params());
        if (StringUtils.hasText(plan.ruleId())) {
            acc.ruleId = plan.ruleId();
        }
    }

    /** 强制路径绑定累积：高优先级已有键不覆盖 */
    private static final class BindingAcc {
        private final ExecutionMode locked;
        private final String reason;
        private String workflowId;
        private final Map<String, String> params = new LinkedHashMap<>();
        private String ruleId;
        private boolean sameModeRuleHit;

        private BindingAcc(ExecutionMode locked, String reason) {
            this.locked = locked;
            this.reason = reason;
        }

        private void mergeParamsFillGaps(Map<String, String> incoming) {
            if (incoming == null || incoming.isEmpty()) {
                return;
            }
            for (Map.Entry<String, String> e : incoming.entrySet()) {
                if (!StringUtils.hasText(e.getKey()) || !StringUtils.hasText(e.getValue())) {
                    continue;
                }
                params.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        private void mergeFromL3(ExecutionPlan llm) {
            if (llm == null) {
                return;
            }
            if (locked == ExecutionMode.WORKFLOW
                    && !StringUtils.hasText(workflowId)
                    && StringUtils.hasText(llm.workflowId())) {
                workflowId = llm.workflowId().strip();
            }
            mergeParamsFillGaps(llm.params());
        }

        private boolean needsL3() {
            return switch (locked) {
                case WORKFLOW -> !StringUtils.hasText(workflowId);
                case FAST -> !StringUtils.hasText(params.get(PARAM_REACT_PROMPT));
                case PRO -> !sameModeRuleHit && !StringUtils.hasText(params.get(SkillBindingOutcome.PARAM_SKILL));
            };
        }

        private ExecutionPlan toPlan() {
            return new ExecutionPlan(
                    locked,
                    workflowId,
                    params.isEmpty() ? Map.of() : Map.copyOf(params),
                    reason,
                    ruleId);
        }
    }
}
