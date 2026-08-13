package com.sunshine.orchestrator.routing;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.policy.AgentBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.UnifiedRuleRoutingPolicy;
import com.sunshine.orchestrator.routing.policy.WorkflowBindingRoutingPolicy;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.workflow.WorkflowBindingOutcome;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 钉死 executionMode 的分轨资源收集：
 * 轨 A（FAST|PRO）skill/agent；轨 B（WORKFLOW）仅 workflowId；永不改 mode。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForcedExecutionRouter {

    private static final String REASON_REACT = "user:forced-react";
    private static final String REASON_PLAN = "user:forced-plan-workflow";
    private static final String REASON_WORKFLOW = "user:forced-workflow";
    private static final String PARAM_REACT_PROMPT = "reactPromptId";
    private static final String PARAM_AGENT_IDS = "agentIds";

    private final SkillBindingRoutingPolicy skillBindingRoutingPolicy;
    private final AgentBindingRoutingPolicy agentBindingRoutingPolicy;
    private final PromptCatalogHolder promptCatalogHolder;
    private final IntentRouter intentRouter;
    private final WorkflowBindingRoutingPolicy workflowBindingRoutingPolicy;
    private final WorkflowBindingParser workflowBindingParser;

    public Mono<ExecutionPlan> resolve(RoutingContext ctx, ExecutionPreference preference, String workflowId) {
        if (preference == null) {
            return Mono.error(new IllegalStateException("ForcedExecutionRouter 需要非空 preference"));
        }
        return switch (preference) {
            case FAST -> resolveForced(ctx, ExecutionMode.FAST, REASON_REACT);
            case PRO -> resolveForced(ctx, ExecutionMode.PRO, REASON_PLAN);
            case WORKFLOW -> resolveWorkflowPinned(ctx, workflowId);
        };
    }

    /** WORKFLOW：先 #mention / 请求体 workflowId，再同轨规则与 L3；无候选显式失败 */
    private Mono<ExecutionPlan> resolveWorkflowPinned(RoutingContext ctx, String workflowId) {
        RoutingContext bindCtx = ctx;
        if (StringUtils.hasText(workflowId) && !StringUtils.hasText(ctx.forcedWorkflowId())) {
            bindCtx = ctx.withForcedWorkflowId(workflowId.strip());
        }
        RoutingContext lockedCtx = bindCtx.withLockedMode(ExecutionMode.WORKFLOW);
        return workflowBindingRoutingPolicy.tryRoute(lockedCtx).flatMap(opt -> {
            if (opt.isPresent()) {
                return Mono.just(opt.get());
            }
            return resolveForced(ctx, ExecutionMode.WORKFLOW, REASON_WORKFLOW);
        });
    }

    private Mono<ExecutionPlan> resolveForced(RoutingContext ctx, ExecutionMode locked, String reason) {
        RoutingContext lockedCtx = ctx.withLockedMode(locked);
        if (locked == ExecutionMode.FAST || locked == ExecutionMode.PRO) {
            warnAndIgnoreHashWorkflow(lockedCtx);
        }
        BindingAcc acc = new BindingAcc(locked, reason);
        Mono<BindingAcc> pipeline = Mono.just(acc);
        if (lockedCtx.allowsSkillBinding()) {
            pipeline = pipeline.flatMap(a -> agentBindingRoutingPolicy.tryRoute(lockedCtx).map(opt -> {
                opt.ifPresent(agentPlan -> a.mergeParamsFillGaps(agentPlan.params()));
                return a;
            }));
            pipeline = pipeline.flatMap(a -> skillBindingRoutingPolicy.tryRoute(lockedCtx).map(opt -> {
                opt.ifPresent(skillPlan -> a.mergeParamsFillGaps(skillPlan.params()));
                return a;
            }));
        }
        return pipeline.flatMap(a -> {
            applyTrackRule(lockedCtx.userMessage(), a);
            if (!a.needsL3()) {
                return Mono.just(a.toPlan());
            }
            return intentRouter.classifyPlan(lockedCtx)
                    .flatMap(llm -> {
                        a.mergeFromL3(IntentRouter.applyLockedMode(llm, locked));
                        if (locked == ExecutionMode.WORKFLOW && !StringUtils.hasText(a.workflowId)) {
                            return Mono.error(new BizException(OrchestratorErrorCode.WORKFLOW_TEMPLATE_NOT_FOUND));
                        }
                        return Mono.just(a.toPlan());
                    });
        });
    }

    private void warnAndIgnoreHashWorkflow(RoutingContext ctx) {
        WorkflowBindingOutcome peek = workflowBindingParser.resolve(ctx.userMessage(), ctx.forcedWorkflowId());
        if (peek.bound() || peek.unknown()
                || (StringUtils.hasText(ctx.userMessage()) && ctx.userMessage().strip().startsWith("#"))) {
            log.warn("[ForcedExecutionRouter] Track A ignores #workflow kind={} messageId={}",
                    ctx.kindOrDefault(), ctx.traceMessageId());
        }
    }

    private void applyTrackRule(String userMessage, BindingAcc acc) {
        Optional<ExecutionPlan> hit = UnifiedRuleRoutingPolicy.matchForLockedMode(
                promptCatalogHolder.snapshot().routingRules(), userMessage, acc.locked);
        if (hit.isEmpty()) {
            return;
        }
        ExecutionPlan plan = hit.get();
        acc.sameModeRuleHit = true;
        if (acc.locked == ExecutionMode.WORKFLOW
                && !StringUtils.hasText(acc.workflowId)
                && StringUtils.hasText(plan.workflowId())) {
            acc.workflowId = plan.workflowId().strip();
        }
        if (acc.locked != ExecutionMode.WORKFLOW) {
            acc.mergeParamsFillGaps(plan.params());
        } else {
            acc.mergeParamsFillGaps(stripTrackAParams(plan.params()));
        }
        if (StringUtils.hasText(plan.ruleId())) {
            acc.ruleId = plan.ruleId();
        }
    }

    private static Map<String, String> stripTrackAParams(Map<String, String> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            if (SkillBindingOutcome.PARAM_SKILL.equals(e.getKey())
                    || PARAM_AGENT_IDS.equals(e.getKey())
                    || SkillBindingOutcome.PARAM_PLANNER_MODE.equals(e.getKey())
                    || PARAM_REACT_PROMPT.equals(e.getKey())) {
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 钉死路径绑定累积：高优先级已有键不覆盖 */
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
                if (locked == ExecutionMode.WORKFLOW
                        && (SkillBindingOutcome.PARAM_SKILL.equals(e.getKey())
                        || PARAM_AGENT_IDS.equals(e.getKey())
                        || SkillBindingOutcome.PARAM_PLANNER_MODE.equals(e.getKey())
                        || PARAM_REACT_PROMPT.equals(e.getKey()))) {
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
                    locked == ExecutionMode.WORKFLOW ? workflowId : null,
                    params.isEmpty() ? Map.of() : Map.copyOf(params),
                    reason,
                    ruleId);
        }
    }
}
