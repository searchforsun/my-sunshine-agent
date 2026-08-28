package com.sunshine.orchestrator.routing;

import java.util.List;

/**
 * 上轮 RoutingResult 轻 sticky seed（skill-sticky S-1）。
 * 仅承载已触发 skillIds 与可调度 agentIds，不存可发现目录（目录每轮由 Catalog 运行时解析）。
 */
public record RoutingSeed(List<String> skillIds, List<String> agentIds) {

    public static final RoutingSeed EMPTY = new RoutingSeed(List.of(), List.of());

    public RoutingSeed {
        skillIds = skillIds != null ? List.copyOf(skillIds) : List.of();
        agentIds = agentIds != null ? List.copyOf(agentIds) : List.of();
    }

    public boolean hasAny() {
        return !skillIds.isEmpty() || !agentIds.isEmpty();
    }
}
