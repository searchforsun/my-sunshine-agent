package com.sunshine.orchestrator.routing;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.SkillBindingRoutingPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 用户强制 executionPreference 时的 ExecutionPlan 产出 */
@Component
@RequiredArgsConstructor
public class ForcedExecutionRouter {

    private static final String REASON_SIMPLE = "user:forced-simple-llm";
    private static final String REASON_REACT = "user:forced-react";
    private static final String REASON_PLAN = "user:forced-plan-workflow";
    private static final String REASON_WORKFLOW = "user:forced-workflow";

    private final SkillBindingRoutingPolicy skillBindingRoutingPolicy;
    private final RuleBasedRouter ruleBasedRouter;
    private final IntentRouter intentRouter;

    public Mono<ExecutionPlan> resolve(RoutingContext ctx, ExecutionPreference preference, String workflowId) {
        if (preference == null || preference == ExecutionPreference.AUTO) {
            return Mono.error(new IllegalStateException("ForcedExecutionRouter 仅处理非 auto preference"));
        }
        return switch (preference) {
            case SIMPLE_LLM -> Mono.just(new ExecutionPlan(
                    ExecutionMode.SIMPLE_LLM, null, Map.of(), REASON_SIMPLE));
            case REACT -> skillOrFallback(ctx, new ExecutionPlan(
                    ExecutionMode.REACT, null, Map.of(), REASON_REACT));
            case PLAN_WORKFLOW -> skillOrFallback(ctx, new ExecutionPlan(
                    ExecutionMode.PLAN_WORKFLOW, null, Map.of(), REASON_PLAN));
            case WORKFLOW -> resolveWorkflow(ctx.userMessage(), workflowId);
            default -> Mono.error(new IllegalStateException("unsupported preference: " + preference));
        };
    }

    private Mono<ExecutionPlan> skillOrFallback(RoutingContext ctx, ExecutionPlan fallback) {
        return skillBindingRoutingPolicy.tryRoute(ctx)
                .flatMap(opt -> {
                    if (opt.isEmpty()) {
                        return Mono.just(fallback);
                    }
                    ExecutionPlan skillPlan = opt.get();
                    if (!isForced(fallback)) {
                        return Mono.just(skillPlan);
                    }
                    // 强制模式：保留 mode/reason，仅合并 L0 @skill 参数（单步 @skill 默认 REACT，不得覆盖 forced plan）
                    Map<String, String> params = new LinkedHashMap<>();
                    if (fallback.params() != null) {
                        params.putAll(fallback.params());
                    }
                    if (skillPlan.params() != null) {
                        params.putAll(skillPlan.params());
                    }
                    return Mono.just(new ExecutionPlan(
                            fallback.mode(),
                            fallback.workflowId(),
                            params,
                            fallback.reason(),
                            fallback.ruleId()));
                });
    }

    private static boolean isForced(ExecutionPlan plan) {
        return plan.reason() != null && plan.reason().startsWith("user:forced");
    }

    private Mono<ExecutionPlan> resolveWorkflow(String userMessage, String workflowId) {
        if (StringUtils.hasText(workflowId)) {
            return Mono.just(new ExecutionPlan(
                    ExecutionMode.WORKFLOW, workflowId.strip(), Map.of(), REASON_WORKFLOW));
        }
        Optional<ExecutionPlan> ruleHit = ruleBasedRouter.match(userMessage).filter(p -> p.mode() == ExecutionMode.WORKFLOW);
        if (ruleHit.isPresent()) {
            ExecutionPlan plan = ruleHit.get();
            return Mono.just(new ExecutionPlan(
                    plan.mode(), plan.workflowId(), plan.params(), REASON_WORKFLOW, plan.ruleId()));
        }
        return intentRouter.classifyPlan(userMessage)
                .flatMap(plan -> {
                    if (plan.mode() == ExecutionMode.WORKFLOW && StringUtils.hasText(plan.workflowId())) {
                        return Mono.just(new ExecutionPlan(
                                ExecutionMode.WORKFLOW,
                                plan.workflowId(),
                                plan.params() != null ? plan.params() : Map.of(),
                                REASON_WORKFLOW,
                                plan.ruleId()));
                    }
                    return Mono.error(new BizException(OrchestratorErrorCode.WORKFLOW_TEMPLATE_NOT_FOUND));
                });
    }
}
