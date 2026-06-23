package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.WorkflowProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 从 sunshine-workflows.yaml 与 tool catalog 解析节点/工作流展示名
 */
@Service
@RefreshScope
@RequiredArgsConstructor
public class WorkflowNodeLabelService {

    private final WorkflowProperties workflowProperties;
    private final ToolCatalogService toolCatalogService;

    /** 动态 Plan 执行期节点展示名（nodeId → displayName） */
    private final ThreadLocal<java.util.Map<String, String>> runtimeNodeLabels = new ThreadLocal<>();

    @PostConstruct
    void init() {
        WorkflowNodeLabels.bind(this);
    }

    public void bindRuntimeNodeLabels(WorkflowDefinition def) {
        if (def == null || def.nodesById() == null) {
            return;
        }
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        for (var entry : def.nodesById().entrySet()) {
            NodeSpec spec = entry.getValue();
            if (spec == null) {
                continue;
            }
            if (StringUtils.hasText(spec.displayName())) {
                labels.put(entry.getKey(), spec.displayName().strip());
            } else {
                labels.put(entry.getKey(), displayNameWithoutRuntime(entry.getKey(), spec.type()));
            }
        }
        runtimeNodeLabels.set(labels);
    }

    public void clearRuntimeNodeLabels() {
        runtimeNodeLabels.remove();
    }

    public String workflowDisplayName(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            return "未知工作流";
        }
        if (workflowProperties.getCatalog() == null) {
            return workflowId;
        }
        return workflowProperties.getCatalog().stream()
                .filter(e -> workflowId.equals(e.getId()))
                .findFirst()
                .map(e -> StringUtils.hasText(e.getDisplayName()) ? e.getDisplayName() : e.getDesc())
                .filter(StringUtils::hasText)
                .orElse(workflowId);
    }

    public String displayName(String nodeId, String nodeType) {
        java.util.Map<String, String> runtime = runtimeNodeLabels.get();
        if (runtime != null && StringUtils.hasText(nodeId)) {
            String bound = runtime.get(nodeId);
            if (StringUtils.hasText(bound)) {
                return bound;
            }
        }
        return displayNameWithoutRuntime(nodeId, nodeType);
    }

    private String displayNameWithoutRuntime(String nodeId, String nodeType) {
        String effectiveType = nodeType != null ? nodeType : resolveNodeType(nodeId);
        String fromDefinition = resolveFromDefinitions(nodeId, effectiveType);
        if (StringUtils.hasText(fromDefinition)) {
            return fromDefinition;
        }
        if (effectiveType == null) {
            return friendlyNameForKnownNodeId(nodeId);
        }
        return switch (effectiveType) {
            case "rag" -> "检索知识库";
            case "llm" -> "综合分析";
            case "agent" -> "智能体分析";
            case "answer" -> "汇总输出";
            case "tool" -> nodeId != null
                    ? toolCatalogService.displayName(resolveBoundTool(nodeId))
                    : "调用工具";
            default -> nodeId != null ? nodeId : effectiveType;
        };
    }

    /** stepId 形如 node-llm，需解析节点 type 才能得到中文名（勿直接暴露 llm/agent 等内部类型） */
    public String displayNameByStepId(String stepId) {
        if (!StringUtils.hasText(stepId)) {
            return "";
        }
        String nodeId = stepId.startsWith("node-") ? stepId.substring("node-".length()) : stepId;
        return displayName(nodeId, resolveNodeType(nodeId));
    }

    private String resolveNodeType(String nodeId) {
        if (!StringUtils.hasText(nodeId)) {
            return null;
        }
        if (workflowProperties.getDefinitions() != null) {
            for (WorkflowProperties.WorkflowDefinitionProps def : workflowProperties.getDefinitions().values()) {
                if (def.getNodes() == null) {
                    continue;
                }
                for (WorkflowProperties.NodeProps node : def.getNodes()) {
                    if (nodeId.equals(node.getId()) && StringUtils.hasText(node.getType())) {
                        return node.getType();
                    }
                }
            }
        }
        return switch (nodeId) {
            case "llm", "rag", "agent" -> nodeId;
            default -> null;
        };
    }

    private static String friendlyNameForKnownNodeId(String nodeId) {
        if (!StringUtils.hasText(nodeId)) {
            return "节点";
        }
        return switch (nodeId) {
            case "llm" -> "生成回答";
            case "rag" -> "检索知识库";
            case "agent" -> "智能体分析";
            default -> nodeId;
        };
    }

    private String resolveFromDefinitions(String nodeId, String nodeType) {
        if (!StringUtils.hasText(nodeId) || workflowProperties.getDefinitions() == null) {
            return null;
        }
        for (WorkflowProperties.WorkflowDefinitionProps def : workflowProperties.getDefinitions().values()) {
            if (def.getNodes() == null) {
                continue;
            }
            for (WorkflowProperties.NodeProps node : def.getNodes()) {
                if (!nodeId.equals(node.getId())) {
                    continue;
                }
                if (StringUtils.hasText(node.getDisplayName())) {
                    return node.getDisplayName();
                }
                if ("tool".equals(nodeType) || "tool".equals(node.getType())) {
                    Object tool = node.getParams() != null ? node.getParams().get("tool") : null;
                    if (tool != null) {
                        return toolCatalogService.displayName(String.valueOf(tool));
                    }
                }
            }
        }
        return null;
    }

    private String resolveBoundTool(String nodeId) {
        if (workflowProperties.getDefinitions() == null) {
            return nodeId;
        }
        for (WorkflowProperties.WorkflowDefinitionProps def : workflowProperties.getDefinitions().values()) {
            if (def.getNodes() == null) {
                continue;
            }
            for (WorkflowProperties.NodeProps node : def.getNodes()) {
                if (nodeId.equals(node.getId()) && node.getParams() != null) {
                    Object tool = node.getParams().get("tool");
                    if (tool != null) {
                        return String.valueOf(tool);
                    }
                }
            }
        }
        return nodeId;
    }

    public String planChain(WorkflowDefinition def) {
        bindRuntimeNodeLabels(def);
        try {
            return def.linearOrder().stream()
                    .filter(nodeId -> {
                        NodeSpec spec = def.node(nodeId);
                        return spec != null && WorkflowNodeLabels.isVisibleNode(spec.type());
                    })
                    .map(nodeId -> displayName(nodeId, def.node(nodeId).type()))
                    .collect(Collectors.joining(" → "));
        } finally {
            clearRuntimeNodeLabels();
        }
    }
}
