package com.sunshine.orchestrator.execution.retry;

import com.sunshine.common.tool.PlanWorkflowExecutionPolicy;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.catalog.WorkflowNodeDefaultsRegistry;
import com.sunshine.orchestrator.execution.NodeSpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;

/** 节点 params retry.* > workflow-manager Nacos 默认策略 */
@Component
public class NodeRetryPolicyResolver {

    private final WorkflowNodeDefaultsRegistry nodeDefaultsRegistry;
    private final ToolSetResolver toolSetResolver;

    public NodeRetryPolicyResolver(
            WorkflowNodeDefaultsRegistry nodeDefaultsRegistry,
            ToolSetResolver toolSetResolver) {
        this.nodeDefaultsRegistry = nodeDefaultsRegistry;
        this.toolSetResolver = toolSetResolver;
    }

    public NodeRetryPolicy resolve(NodeSpec spec, boolean planWorkflow, String tenantId) {
        PlanWorkflowExecutionPolicy policy = nodeDefaultsRegistry.policy();
        PlanWorkflowExecutionPolicy.NodeDefaults defaults = policy.defaults();
        String type = spec.type() != null ? spec.type() : "";
        PlanWorkflowExecutionPolicy.NodeTypeOverride typeOverride =
                policy.byType() != null ? policy.byType().get(type) : null;
        int maxAttempts = firstPositive(
                paramInt(spec, "retry.maxAttempts"),
                typeOverride != null && typeOverride.maxAttempts() != null ? typeOverride.maxAttempts() : 0,
                defaults.maxAttempts(),
                1);
        long backoffMs = firstPositiveLong(
                paramLong(spec, "retry.backoffMs"),
                defaults.backoffMs(),
                0L);
        double multiplier = defaults.backoffMultiplier() > 0
                ? defaults.backoffMultiplier() : 2.0;
        OnFailureAction onFailure = resolveOnFailure(spec, type, policy, defaults, tenantId);
        Set<String> retryOn = new HashSet<>(defaults.retryOnErrorClass());
        return new NodeRetryPolicy(maxAttempts, backoffMs, multiplier, onFailure, retryOn);
    }

    private OnFailureAction resolveOnFailure(
            NodeSpec spec,
            String type,
            PlanWorkflowExecutionPolicy policy,
            PlanWorkflowExecutionPolicy.NodeDefaults defaults,
            String tenantId) {
        String param = readParamString(spec, "retry.onFailure");
        if (StringUtils.hasText(param)) {
            return OnFailureAction.fromConfig(param);
        }
        String tool = readParamString(spec, "tool");
        if (StringUtils.hasText(tool)) {
            Set<String> criticalTools = new HashSet<>(toolSetResolver.resolveTaskCriticalTools(tenantId));
            if (criticalTools.contains(tool.strip())) {
                return OnFailureAction.fromConfig(policy.criticalOnFailure());
            }
        }
        PlanWorkflowExecutionPolicy.NodeTypeOverride typeOverride =
                policy.byType() != null ? policy.byType().get(type) : null;
        if (typeOverride != null && StringUtils.hasText(typeOverride.onFailure())) {
            return OnFailureAction.fromConfig(typeOverride.onFailure());
        }
        return OnFailureAction.fromConfig(defaults.onFailure());
    }

    private static int firstPositive(int... values) {
        for (int v : values) {
            if (v > 0) {
                return v;
            }
        }
        return 1;
    }

    private static long firstPositiveLong(long primary, long fallback, long defaultVal) {
        if (primary > 0) {
            return primary;
        }
        if (fallback > 0) {
            return fallback;
        }
        return defaultVal;
    }

    private static int paramInt(NodeSpec spec, String key) {
        if (spec.params() == null) {
            return 0;
        }
        String raw = readParamString(spec, key);
        if (!StringUtils.hasText(raw)) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long paramLong(NodeSpec spec, String key) {
        return paramInt(spec, key);
    }

    /** 从 Map<String,Object> params 读取字符串值（兼容 Object 值） */
    private static String readParamString(NodeSpec spec, String key) {
        if (spec.params() == null) {
            return null;
        }
        Object v = spec.params().get(key);
        return v != null ? v.toString() : null;
    }
}
