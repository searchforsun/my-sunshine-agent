package com.sunshine.orchestrator.skill;

import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.springframework.stereotype.Component;

/** 流程 3 后置：校验 L3 输出的 skillId，不做关键词自动发现 */
@Component
public class SkillDiscoveryService {

    private final SkillCatalogService skillCatalogService;

    public SkillDiscoveryService(SkillCatalogService skillCatalogService) {
        this.skillCatalogService = skillCatalogService;
    }

    public ExecutionPlan enrich(ExecutionPlan plan, String userMessage) {
        return skillCatalogService.sanitizeSkillPlan(plan);
    }
}
