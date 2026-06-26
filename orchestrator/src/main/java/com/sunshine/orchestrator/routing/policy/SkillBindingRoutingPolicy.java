package com.sunshine.orchestrator.routing.policy;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.StructuralPlanMatcher;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import com.sunshine.orchestrator.skill.SkillBindingSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** L0：@ / 强提示 Skill 硬绑定 */
@Component
@RequiredArgsConstructor
public class SkillBindingRoutingPolicy implements RoutingPolicy {

    private final SkillBindingParser skillBindingParser;
    private final StructuralPlanMatcher structuralPlanMatcher;

    @Override
    public int order() {
        return 0;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        if (!ctx.allowsSkillBinding()) {
            return Mono.just(Optional.empty());
        }
        SkillBindingOutcome binding = StringUtils.hasText(ctx.clientSkillId())
                ? skillBindingParser.parse(ctx.userMessage(), ctx.clientSkillId())
                : skillBindingParser.parse(ctx.userMessage());
        if (binding.unknown()) {
            return Mono.error(new BizException(OrchestratorErrorCode.SKILL_NOT_FOUND));
        }
        if (!binding.bound()) {
            return Mono.just(Optional.empty());
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put(SkillBindingOutcome.PARAM_SKILL, binding.skillId());
        params.put(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY, binding.effectiveQuery());
        String reason = switch (binding.source()) {
            case AT_MENTION -> "skill:@mention";
            case HINT_PATTERN -> "skill:hint";
            case CLIENT -> "skill:client";
        };
        if (structuralPlanMatcher.looksLikeMultiStepPlan(binding.effectiveQuery())) {
            params.put(SkillBindingOutcome.PARAM_PLANNER_MODE, SkillBindingOutcome.PLANNER_MODE_SKILL_DRIVEN);
            return Mono.just(Optional.of(new ExecutionPlan(
                    ExecutionMode.PLAN_WORKFLOW, null, params, reason + ":5b-skill-plan")));
        }
        return Mono.just(Optional.of(new ExecutionPlan(ExecutionMode.REACT, null, params, reason)));
    }
}
