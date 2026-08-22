package com.sunshine.orchestrator.skill;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.springframework.stereotype.Component;

/**
 * 流程 3 后置：校验 L3 输出的 skillId；L2 统一召回后按轨过滤 resourceType（最小实现）。
 */
@Component
public class SkillDiscoveryService {

    private final SkillCatalogService skillCatalogService;

    public SkillDiscoveryService(SkillCatalogService skillCatalogService) {
        this.skillCatalogService = skillCatalogService;
    }

    public ExecutionPlan enrich(ExecutionPlan plan) {
        return skillCatalogService.sanitizeSkillPlan(plan);
    }

    /** 召回/合并后按轨裁剪：轨 A 去 workflowId；轨 B 去 skill/agent */
    public ExecutionPlan filterForTrack(ExecutionPlan plan, ExecutionMode mode) {
        return IntentRouter.applyLockedMode(plan, mode);
    }
}
