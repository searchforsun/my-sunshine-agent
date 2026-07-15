package com.sunshine.orchestrator.execution;

import com.sunshine.common.workflow.WorkflowNodeType;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.stream.Collectors;

/**
 * 从 DB workflow catalog、tool catalog 与代码内置节点 type 默认名解析展示名
 */
@Service
@RefreshScope
@RequiredArgsConstructor
public class WorkflowNodeLabelService {

    private final WorkflowCatalog workflowCatalog;
    private final ToolCatalogService toolCatalogService;

    /** 动态 Plan 执行期节点展示名（nodeId → displayName） */
    private final ThreadLocal<java.util.Map<String, String>> runtimeNodeLabels = new ThreadLocal<>();
    private final ThreadLocal<java.util.Map<String, String>> runtimeToolIds = new ThreadLocal<>();

    @PostConstruct
    void init() {
        WorkflowNodeLabels.bind(this);
    }

    public void bindRuntimeNodeLabels(WorkflowDefinition def) {
        if (def == null || def.nodesById() == null) {
            return;
        }
        java.util.Map<String, String> labels = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> tools = new java.util.LinkedHashMap<>();
        for (var entry : def.nodesById().entrySet()) {
            NodeSpec spec = entry.getValue();
            if (spec == null) {
                continue;
            }
            if (WorkflowNodeType.TOOL.matches(spec.type()) && spec.params() != null) {
                String tool = spec.params().get("tool");
                if (StringUtils.hasText(tool)) {
                    tools.put(entry.getKey(), tool.strip());
                }
            }
            if (StringUtils.hasText(spec.displayName())) {
                labels.put(entry.getKey(), spec.displayName().strip());
            } else {
                labels.put(entry.getKey(), displayNameWithoutRuntime(entry.getKey(), spec.type()));
            }
        }
        runtimeNodeLabels.set(labels);
        runtimeToolIds.set(tools);
    }

    public void clearRuntimeNodeLabels() {
        runtimeNodeLabels.remove();
        runtimeToolIds.remove();
    }

    public String workflowDisplayName(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            return WorkflowTimelineLabels.UNKNOWN_WORKFLOW;
        }
        var entry = workflowCatalog.findEntry(workflowId);
        if (entry == null) {
            return workflowId;
        }
        if (StringUtils.hasText(entry.displayName())) {
            return entry.displayName().strip();
        }
        return StringUtils.hasText(entry.description()) ? entry.description().strip() : workflowId;
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

    public String typeLabel(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return WorkflowTimelineLabels.UNKNOWN_NODE;
        }
        return WorkflowNodeType.of(nodeType)
                .map(this::labelForKnownType)
                .orElseGet(() -> WorkflowTimelineLabels.typeLabel(nodeType));
    }

    private String labelForKnownType(WorkflowNodeType type) {
        String raw = switch (type) {
            case RAG -> WorkflowTimelineLabels.TYPE_RAG;
            case LLM -> WorkflowTimelineLabels.TYPE_LLM;
            case AGENT -> WorkflowTimelineLabels.TYPE_AGENT;
            case ANSWER -> WorkflowTimelineLabels.TYPE_ANSWER;
            case TOOL -> WorkflowTimelineLabels.TYPE_TOOL;
            case JOIN -> WorkflowTimelineLabels.TYPE_JOIN;
            case PARALLEL_GATEWAY -> WorkflowTimelineLabels.TYPE_PARALLEL_GATEWAY;
            case EXCLUSIVE_GATEWAY -> WorkflowTimelineLabels.TYPE_EXCLUSIVE_GATEWAY;
            default -> type.id();
        };
        return StringUtils.hasText(raw) ? raw.strip() : type.id();
    }

    public String subAgentDefaultLabel() {
        return WorkflowTimelineLabels.SUB_AGENT_DEFAULT;
    }

    /** stepId 形如 node-llm，需解析节点 type 才能得到中文名（勿直接暴露 llm/agent 等内部类型） */
    public String displayNameByStepId(String stepId) {
        if (!StringUtils.hasText(stepId)) {
            return "";
        }
        String nodeId = stepId.startsWith("node-") ? stepId.substring("node-".length()) : stepId;
        return displayName(nodeId, resolveNodeType(nodeId));
    }

    private String displayNameWithoutRuntime(String nodeId, String nodeType) {
        String effectiveType = nodeType != null ? nodeType : resolveNodeType(nodeId);
        if (effectiveType == null) {
            return friendlyNameForKnownNodeId(nodeId);
        }
        if (WorkflowNodeType.TOOL.matches(effectiveType)) {
            return nodeId != null
                    ? toolCatalogService.displayName(resolveBoundTool(nodeId))
                    : typeLabel(effectiveType);
        }
        return typeLabel(effectiveType);
    }

    private String resolveNodeType(String nodeId) {
        if (!StringUtils.hasText(nodeId)) {
            return null;
        }
        java.util.Map<String, String> runtime = runtimeNodeLabels.get();
        if (runtime != null && runtime.containsKey(nodeId)) {
            return WorkflowNodeType.of(nodeId).map(WorkflowNodeType::id).orElse(null);
        }
        return WorkflowNodeType.of(nodeId).map(WorkflowNodeType::id).orElse(null);
    }

    private String friendlyNameForKnownNodeId(String nodeId) {
        if (!StringUtils.hasText(nodeId)) {
            return WorkflowTimelineLabels.UNKNOWN_NODE;
        }
        return WorkflowNodeType.of(nodeId)
                .map(type -> typeLabel(type.id()))
                .orElse(nodeId);
    }

    private String resolveBoundTool(String nodeId) {
        java.util.Map<String, String> tools = runtimeToolIds.get();
        if (tools != null && StringUtils.hasText(nodeId)) {
            String tool = tools.get(nodeId);
            if (StringUtils.hasText(tool)) {
                return tool;
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
                        return spec != null && WorkflowNodeType.isPlanChainNode(spec.type());
                    })
                    .map(nodeId -> displayName(nodeId, def.node(nodeId).type()))
                    .collect(Collectors.joining(" → "));
        } finally {
            clearRuntimeNodeLabels();
        }
    }
}
