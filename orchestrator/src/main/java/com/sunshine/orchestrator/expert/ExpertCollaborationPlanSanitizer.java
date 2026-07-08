package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.springframework.stereotype.Component;

/** peer-collab 计划校验 — 不再依赖 Nacos peer.templates */
@Component
public class ExpertCollaborationPlanSanitizer {
    public ExecutionPlan sanitize(ExecutionPlan plan) {
        if (plan == null || plan.mode() != ExecutionMode.PEER_COLLAB) {
            return plan;
        }
        return plan;
    }
}
