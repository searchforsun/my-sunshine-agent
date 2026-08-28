package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻 sticky 合并（skill-sticky S-1）：只粘触发。
 * 本轮无新触发 → 继承上轮已触发 skill / 可调度 agent；L0/L3 新触发 → 整表替换。
 * Workflow 轨不做 skill sticky（spec §2.2）。
 */
@Component
public class RoutingStickyService {

    public ExecutionPlan applySeed(ExecutionPlan plan, RoutingSeed seed) {
        if (plan == null || seed == null || !seed.hasAny() || plan.mode() == ExecutionMode.WORKFLOW) {
            return plan;
        }
        Map<String, String> params = plan.params() != null ? plan.params() : Map.of();
        if (plan.triggeredSkillIds().isEmpty() && !seed.skillIds().isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(params);
            merged.put(ExecutionPlan.PARAM_SKILL_IDS, String.join(",", seed.skillIds()));
            if (!StringUtils.hasText(merged.get(SkillBindingOutcome.PARAM_SKILL))) {
                merged.put(SkillBindingOutcome.PARAM_SKILL, seed.skillIds().get(0));
            }
            params = merged;
        }
        if (plan.schedulableAgentIds().isEmpty() && !seed.agentIds().isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(params);
            merged.put(ExecutionPlan.PARAM_AGENT_IDS, String.join(",", seed.agentIds()));
            params = merged;
        }
        if (params == plan.params()) {
            return plan;
        }
        return new ExecutionPlan(
                plan.mode(),
                plan.workflowId(),
                params.isEmpty() ? Map.of() : Map.copyOf(params),
                plan.reason(),
                plan.ruleId(),
                plan.routingTraces());
    }
}
