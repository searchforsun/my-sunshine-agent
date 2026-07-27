package com.sunshine.orchestrator.execution.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.workflow.WorkflowNodeType;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TemplateResolver;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 变量赋值节点 - 从 params.assignments（JSON 数组）解析赋值列表，
 * 每个 assignment 的 source 经 TemplateResolver 解析后以 name 为字段输出。
 */
@Slf4j
@Component
public class VariableAssignmentNodeHandler implements NodeHandler {

    private static final ObjectMapper OM = new ObjectMapper();

    @Override
    public String type() {
        return WorkflowNodeType.VARIABLE_ASSIGNMENT.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, Object> params = spec.params() != null ? spec.params() : Map.of();
        String assignmentsJson = params.getOrDefault("assignments", "[]").toString();
        Map<String, TypedValue> outputs = new LinkedHashMap<>();
        try {
            JsonNode arr = OM.readTree(assignmentsJson);
            if (!arr.isArray()) {
                return Mono.just(NodeResult.fail("assignments 不是 JSON 数组"));
            }
            for (JsonNode item : arr) {
                String name = item.get("name").asText();
                String source = item.get("source").asText();
                TypedValue val = TemplateResolver.resolveTyped(source, ctx);
                outputs.put(name, val);
            }
        } catch (Exception e) {
            return Mono.just(NodeResult.fail("assignments 解析失败: " + e.getMessage()));
        }
        return Mono.just(NodeResult.ok(outputs));
    }
}
