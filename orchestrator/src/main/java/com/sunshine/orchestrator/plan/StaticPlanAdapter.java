package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowDefinition;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 静态 Nacos Workflow 定义 → 可持久化 PlanJson（与动态 Plan 同构，供 DAG 展示与审计） */
public final class StaticPlanAdapter {

    private StaticPlanAdapter() {
    }

    public static PlanJson from(WorkflowDefinition def, String routeReason) {
        List<PlanNode> nodes = new ArrayList<>();
        for (String nodeId : def.linearOrder()) {
            NodeSpec spec = def.node(nodeId);
            if (spec == null) {
                continue;
            }
            nodes.add(new PlanNode(
                    spec.id(), spec.type(), spec.params(), spec.displayName()));
        }
        List<PlanEdge> edges = new ArrayList<>();
        List<String> order = def.linearOrder();
        for (int i = 0; i < order.size() - 1; i++) {
            edges.add(new PlanEdge(order.get(i), order.get(i + 1)));
        }
        String reason = StringUtils.hasText(routeReason)
                ? routeReason.strip()
                : "静态工作流 " + def.id();
        // planId 留空，由 ExecutionPlanStore 生成 UUID，避免同 workflow 多次执行主键冲突
        return new PlanJson(null, reason, List.copyOf(nodes), List.copyOf(edges));
    }
}
