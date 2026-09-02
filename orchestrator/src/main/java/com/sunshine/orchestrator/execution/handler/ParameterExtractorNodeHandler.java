package com.sunshine.orchestrator.execution.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.workflow.WorkflowNodeType;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TemplateResolver;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 参数提取节点 - 用 LLM 从上游文本中按 schema 提取结构化字段。
 * Prompt 模板来自 Catalog（parameter-extractor.template），禁止 Java 硬编码兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParameterExtractorNodeHandler implements NodeHandler {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String CATALOG_TEMPLATE_ID = "parameter-extractor.template";

    private final LlmGatewayClient llmGatewayClient;
    private final PromptCatalogHolder promptCatalogHolder;

    @Override
    public String type() {
        return WorkflowNodeType.PARAMETER_EXTRACTOR.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, Object> params = spec.params() != null ? spec.params() : Map.of();
        String inputTemplate = readParamString(params, "input");
        String instruction = readParamString(params, "instruction");
        String schema = readParamString(params, "schema");
        String inputText = TemplateResolver.resolve(inputTemplate, ctx);

        String template = promptCatalogHolder.requireText(CATALOG_TEMPLATE_ID);
        if (template.isBlank()) {
            return Mono.just(NodeResult.fail("Catalog 缺少 " + CATALOG_TEMPLATE_ID));
        }

        // input 作为 userContent 传入 complete()，不在 systemPrompt 中替换 {{input}}
        String systemPrompt = template
                .replace("{{instruction}}", instruction != null ? instruction : "")
                .replace("{{schema}}", schema != null ? schema : "");

        return Mono.fromCallable(() -> llmGatewayClient.complete(systemPrompt, inputText))
                .subscribeOn(VirtualThreadExecutors.scheduler())
                .map(this::buildResult)
                .onErrorResume(e -> {
                    log.warn("[ParameterExtractor] LLM 提取失败: {}", e.getMessage());
                    return Mono.just(NodeResult.fail("参数提取失败: " + e.getMessage()));
                });
    }

    private NodeResult buildResult(String llmResponse) {
        if (llmResponse == null || llmResponse.isBlank()) {
            return NodeResult.fail("LLM 返回空");
        }
        try {
            String json = extractJson(llmResponse);
            JsonNode root = OM.readTree(json);
            if (!root.isObject()) {
                return NodeResult.fail("LLM 返回非 JSON 对象");
            }
            Map<String, TypedValue> outputs = new LinkedHashMap<>();
            outputs.put("output", TypedValue.fromJson(root));
            root.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                outputs.put(e.getKey(), TypedValue.fromJson(v));
            });
            return NodeResult.ok(outputs);
        } catch (Exception e) {
            return NodeResult.fail("LLM 返回 JSON 解析失败: " + e.getMessage());
        }
    }

    /** 从 LLM 响应中提取 JSON 对象（支持 markdown fence 包裹） */
    private static String extractJson(String raw) {
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (firstNl > 0 && end > firstNl) {
                trimmed = trimmed.substring(firstNl + 1, end).strip();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private static String readParamString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v != null ? v.toString() : "";
    }
}
