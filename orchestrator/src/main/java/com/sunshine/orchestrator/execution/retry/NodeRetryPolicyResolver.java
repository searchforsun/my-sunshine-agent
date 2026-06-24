package com.sunshine.orchestrator.execution.retry;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.NodeSpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 从 Nacos + 节点 params 解析重试策略 */
@Component
public class NodeRetryPolicyResolver {

    private final AgentExecutionProperties executionProperties;

    public NodeRetryPolicyResolver(AgentExecutionProperties executionProperties) {
        this.executionProperties = executionProperties;
    }

    public NodeRetryPolicy resolve(NodeSpec spec, boolean planWorkflow) {
        if (!planWorkflow) {
            return NodeRetryPolicy.noRetry(OnFailureAction.CONTINUE);
        }
        AgentExecutionProperties.PlanWorkflow.Node nodeCfg =
                executionProperties.getPlanWorkflow().getNode();
        AgentExecutionProperties.PlanWorkflow.NodeDefaults defaults = nodeCfg.getDefaults();
        String type = spec.type() != null ? spec.type() : "";
        AgentExecutionProperties.PlanWorkflow.NodeTypeOverride typeOverride =
                nodeCfg.getByType().get(type);
        int maxAttempts = firstPositive(
                paramInt(spec, "retry.maxAttempts"),
                typeOverride != null ? typeOverride.getMaxAttempts() : 0,
                defaults.getMaxAttempts(),
                1);
        long backoffMs = firstPositiveLong(
                paramLong(spec, "retry.backoffMs"),
                defaults.getBackoffMs(),
                0L);
        double multiplier = defaults.getBackoffMultiplier() > 0
                ? defaults.getBackoffMultiplier() : 2.0;
        OnFailureAction onFailure = resolveOnFailure(spec, type, nodeCfg, defaults);
        Set<String> retryOn = new HashSet<>(defaults.getRetryOnErrorClass());
        return new NodeRetryPolicy(maxAttempts, backoffMs, multiplier, onFailure, retryOn);
    }

    private OnFailureAction resolveOnFailure(
            NodeSpec spec,
            String type,
            AgentExecutionProperties.PlanWorkflow.Node nodeCfg,
            AgentExecutionProperties.PlanWorkflow.NodeDefaults defaults) {
        String param = spec.params() != null ? spec.params().get("retry.onFailure") : null;
        if (StringUtils.hasText(param)) {
            return OnFailureAction.fromConfig(param);
        }
        String tool = spec.params() != null ? spec.params().get("tool") : null;
        if (StringUtils.hasText(tool) && nodeCfg.getCriticalTools().contains(tool.strip())) {
            return OnFailureAction.fromConfig(nodeCfg.getCriticalOnFailure());
        }
        AgentExecutionProperties.PlanWorkflow.NodeTypeOverride typeOverride =
                nodeCfg.getByType().get(type);
        if (typeOverride != null && StringUtils.hasText(typeOverride.getOnFailure())) {
            return OnFailureAction.fromConfig(typeOverride.getOnFailure());
        }
        return OnFailureAction.fromConfig(defaults.getOnFailure());
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
