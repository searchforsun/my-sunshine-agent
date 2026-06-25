package com.sunshine.orchestrator.skill;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 流程 3：ReAct 路径下按 Skill 目录摘要自动匹配 skillId */
@Component
public class SkillDiscoveryService {

    private static final int MIN_MATCH_SCORE = 2;

    private final SkillCatalogService skillCatalogService;

    public SkillDiscoveryService(SkillCatalogService skillCatalogService) {
        this.skillCatalogService = skillCatalogService;
    }

    public ExecutionPlan enrich(ExecutionPlan plan, String userMessage) {
        if (plan == null || plan.mode() != ExecutionMode.REACT || !StringUtils.hasText(userMessage)) {
            return plan;
        }
        // 用户强制模式不得被自动发现覆盖 reason / skill
        if (plan.reason() != null && plan.reason().startsWith("user:forced")) {
            return plan;
        }
        if (hasSkillParam(plan)) {
            return plan;
        }
        return tryDiscover(userMessage.strip())
                .map(skillId -> withDiscoveredSkill(plan, userMessage.strip(), skillId))
                .orElse(plan);
    }

    Optional<String> tryDiscover(String query) {
        int bestScore = 0;
        String bestId = null;
        for (SkillCatalogIndexEntry entry : skillCatalogService.indexEntries()) {
            if (!entry.enabled()) {
                continue;
            }
            int score = scoreEntry(query, entry);
            if (score > bestScore) {
                bestScore = score;
                bestId = entry.id();
            }
        }
        return bestScore >= MIN_MATCH_SCORE ? Optional.ofNullable(bestId) : Optional.empty();
    }

    private static int scoreEntry(String query, SkillCatalogIndexEntry entry) {
        int score = 0;
        if (query.contains(entry.id())) {
            score += 4;
        }
        if (StringUtils.hasText(entry.displayName()) && query.contains(entry.displayName())) {
            score += 4;
        }
        if (StringUtils.hasText(entry.description())) {
            String desc = entry.description();
            if (query.contains(desc)) {
                score += 6;
            }
            for (int i = 0; i + 2 <= desc.length(); i++) {
                String bigram = desc.substring(i, i + 2);
                if (query.contains(bigram)) {
                    score++;
                }
            }
        }
        return score;
    }

    private static boolean hasSkillParam(ExecutionPlan plan) {
        if (plan.params() == null) {
            return false;
        }
        String skill = plan.params().get(SkillBindingOutcome.PARAM_SKILL);
        return StringUtils.hasText(skill);
    }

    private static ExecutionPlan withDiscoveredSkill(ExecutionPlan plan, String query, String skillId) {
        Map<String, String> params = new LinkedHashMap<>(plan.params() != null ? plan.params() : Map.of());
        params.put(SkillBindingOutcome.PARAM_SKILL, skillId);
        params.put(SkillBindingOutcome.PARAM_EFFECTIVE_QUERY, query);
        return new ExecutionPlan(plan.mode(), plan.workflowId(), params, "skill:auto-discovered", plan.ruleId());
    }
}
