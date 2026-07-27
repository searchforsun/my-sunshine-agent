package com.sunshine.orchestrator.execution.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.common.workflow.WorkflowNodeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 并行汇合节点 - 按 {@code mergeStrategy}（collect/merge/first/last，默认 collect）
 * 聚合 {@code branches} 节点的 output 字段，输出聚合 TypedValue + status=joined。
 */
@Slf4j
@Component
public class JoinNodeHandler implements NodeHandler {

    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public String type() {
        return WorkflowNodeType.JOIN.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, Object> params = spec.params() != null ? spec.params() : Map.of();
        String strategy = params.getOrDefault("mergeStrategy", "collect").toString();
        List<String> branches = parseBranches(params.get("branches"));
        TypedValue merged = aggregate(strategy, branches, ctx);
        Map<String, TypedValue> outputs = new LinkedHashMap<>();
        outputs.put("output", merged);
        outputs.put("status", TypedValue.scalar("joined"));
        return Mono.just(NodeResult.ok(outputs));
    }

    private TypedValue aggregate(String strategy, List<String> branches, WorkflowContext ctx) {
        List<TypedValue> outputs = branches.stream()
                .map(id -> ctx.node(id).get("output"))
                .filter(Objects::nonNull)
                .toList();
        return switch (strategy) {
            case "first" -> outputs.isEmpty() ? TypedValue.fromJson(OM.nullNode()) : outputs.get(0);
            case "last" -> outputs.isEmpty() ? TypedValue.fromJson(OM.nullNode()) : outputs.get(outputs.size() - 1);
            case "merge" -> mergeObjects(outputs);
            default -> collectArray(outputs);
        };
    }

    private TypedValue collectArray(List<TypedValue> outputs) {
        var arr = OM.createArrayNode();
        for (TypedValue v : outputs) {
            arr.add(v.toJson());
        }
        return TypedValue.fromJson(arr);
    }

    private TypedValue mergeObjects(List<TypedValue> outputs) {
        ObjectNode merged = OM.createObjectNode();
        for (TypedValue v : outputs) {
            JsonNode json = v.toJson();
            if (json.isObject()) {
                merged.setAll((ObjectNode) json);
            }
        }
        return TypedValue.fromJson(merged);
    }

    private List<String> parseBranches(Object raw) {
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(raw.toString().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
