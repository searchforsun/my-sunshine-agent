package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.config.WorkflowProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从 WorkflowProperties 加载 DAG 定义
 */
@Component
@RequiredArgsConstructor
public class WorkflowDefinitionLoader {

    private final WorkflowProperties workflowProperties;

    public Optional<WorkflowDefinition> load(String workflowId) {
        if (!StringUtils.hasText(workflowId) || workflowProperties.getDefinitions() == null) {
            return Optional.empty();
        }
        WorkflowProperties.WorkflowDefinitionProps props =
                workflowProperties.getDefinitions().get(workflowId);
        if (props == null || props.getNodes() == null || props.getNodes().isEmpty()) {
            return Optional.empty();
        }
        List<NodeSpec> nodes = new ArrayList<>();
        for (WorkflowProperties.NodeProps np : props.getNodes()) {
            nodes.add(new NodeSpec(np.getId(), np.getType(), toStringParams(np.getParams())));
        }
        List<String> order = props.getEdges() != null && !props.getEdges().isEmpty()
                ? props.getEdges()
                : nodes.stream().map(NodeSpec::id).toList();
        return Optional.of(WorkflowDefinition.from(workflowId, nodes, order));
    }

    private static Map<String, String> toStringParams(Map<String, Object> raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null) {
            return params;
        }
        raw.forEach((k, v) -> {
            if (v == null) {
                params.put(k, "");
            } else if (v instanceof List<?> list) {
                params.put(k, String.join(",", list.stream().map(Object::toString).toList()));
            } else {
                params.put(k, v.toString());
            }
        });
        return params;
    }
}
