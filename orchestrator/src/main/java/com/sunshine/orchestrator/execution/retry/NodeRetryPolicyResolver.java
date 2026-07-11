package com.sunshine.orchestrator.execution.retry;

import com.sunshine.common.tool.PlanWorkflowExecutionPolicy;
import com.sunshine.orchestrator.catalog.PlanWorkflowPolicyResolver;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.execution.NodeSpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;

/** 从工具集 + Nacos 模式策略 + 节点 params 解析重试策略 */
@Component
public class NodeRetryPolicyResolver {

    private final PlanWorkflowPolicyResolver planWorkflowPolicyResolver;
    private final ToolSetResolver toolSetResolver;

    public NodeRetryPolicyResolver(
            PlanWorkflowPolicyResolver planWorkflowPolicyResolver,
            ToolSetResolver toolSetResolver) {
        this.planWorkflowPolicyResolver = planWorkflowPolicyResolver;
        this.toolSetResolver = toolSetResolver;
    }

    public NodeRetryPolicy resolve(NodeSpec spec, boolean planWorkflow, String tenantId) {
        if (!planWorkflow) {
            return NodeRetryPolicy.noRetry(OnFailureAction.CONTINUE);
        }
        PlanWorkflowExecutionPolicy policy = planWorkflowPolicyResolver.resolve(tenantId);
        PlanWorkflowExecutionPolicy.NodeDefaults defaults = policy.defaults() != null
                ? policy.defaults()
                : PlanWorkflowExecutionPolicy.platformDefault().defaults();
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
        Set<String> retryOn = new HashSet<>(defaults.retryOnErrorClass() != null
                ? defaults.retryOnErrorClass()
                : PlanWorkflowExecutionPolicy.platformDefault().defaults().retryOnErrorClass());
        return new NodeRetryPolicy(maxAttempts, backoffMs, multiplier, onFailure, retryOn);
    }

    private OnFailureAction resolveOnFailure(
            NodeSpec spec,
            String type,
            PlanWorkflowExecutionPolicy policy,
            PlanWorkflowExecutionPolicy.NodeDefaults defaults,
            String tenantId) {
        String param = spec.params() != null ? spec.params().get("retry.onFailure") : null;
        if (StringUtils.hasText(param)) {
            return OnFailureAction.fromConfig(param);
        }
        String tool = spec.params() != null ? spec.params().get("tool") : null;
        if (StringUtils.hasText(tool)) {
            Set<String> criticalTools = new HashSet<>(toolSetResolver.resolvePlanWorkflowCriticalTools(tenantId));
            if (criticalTools.contains(tool.strip())) {
                String criticalOnFailure = policy.criticalOnFailure() != null
                        ? policy.criticalOnFailure()
                        : PlanWorkflowExecutionPolicy.platformDefault().criticalOnFailure();
                return OnFailureAction.fromConfig(criticalOnFailure);
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
        String raw = spec.params().get(key);
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
}
