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
 *
 * <p>v3.19 修正：seed 里经 {@code sunshine_search_skills} 运行时动态加载的技能（写入消息
 * {@code routing_skill_ids}）须在后续轮次 sticky 继承，即使本轮 plan 因 L1 规则触发了老技能。
 * 因此对「非 L0 显式绑定」的 plan，seed 技能与 plan 已触发集做<b>并集</b>（动态加载 → 追加，
 * 不因 plan 非空被整表替换）；仅 L0 显式 {@code /skill}（reason 以 {@code skill:} 开头）保留整表替换语义。
 */
@Component
public class RoutingStickyService {

    public ExecutionPlan applySeed(ExecutionPlan plan, RoutingSeed seed) {
        if (plan == null || seed == null || !seed.hasAny() || plan.mode() == ExecutionMode.WORKFLOW) {
            return plan;
        }
        Map<String, String> params = plan.params() != null ? plan.params() : Map.of();
        if (!seed.skillIds().isEmpty() && !isL0ExplicitSkill(plan)) {
            Map<String, String> merged = new LinkedHashMap<>(params);
            // 并集：plan 已触发集 ∪ seed（运行时动态加载）技能，保序去重；
            // 覆盖原「空则继承 / 非空整表替换」，使动态加载技能跨轮不丢。
            merged.put(ExecutionPlan.PARAM_SKILL_IDS, String.join(",",
                    unionSkillIds(plan.triggeredSkillIds(), seed.skillIds())));
            if (!StringUtils.hasText(merged.get(SkillBindingOutcome.PARAM_SKILL))) {
                merged.put(SkillBindingOutcome.PARAM_SKILL, firstOf(plan.triggeredSkillIds(), seed.skillIds()));
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

    /** L0 显式 {@code /skill} 绑定：reason 以 {@code skill:} 开头（skill:/mention|hint|client），整表替换 */
    private static boolean isL0ExplicitSkill(ExecutionPlan plan) {
        return plan.reason() != null && plan.reason().startsWith("skill:");
    }

    private static java.util.List<String> unionSkillIds(
            java.util.List<String> planTriggered, java.util.List<String> seed) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (planTriggered != null) {
            set.addAll(planTriggered);
        }
        if (seed != null) {
            set.addAll(seed);
        }
        return java.util.List.copyOf(set);
    }

    private static String firstOf(java.util.List<String> planTriggered, java.util.List<String> seed) {
        if (planTriggered != null && !planTriggered.isEmpty()) {
            return planTriggered.get(0);
        }
        return seed != null && !seed.isEmpty() ? seed.get(0) : null;
    }
}
