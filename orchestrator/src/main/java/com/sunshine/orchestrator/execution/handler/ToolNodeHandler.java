package com.sunshine.orchestrator.execution.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.sunshine.orchestrator.audit.ToolAuditService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.InputBinding;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TemplateResolver;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.hitl.HitlWaitInterruptedException;
import com.sunshine.orchestrator.hitl.WorkflowHitlScope;
import com.sunshine.common.workflow.WorkflowNodeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用节点 - 从 {@code spec.inputs()} 解析显式输入绑定，调 {@link ToolManagerClient#invokeJsonMono}
 * 获取结构化 JSON 结果；写工具走 HITL（streamCtx.workflowHitl）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolNodeHandler implements NodeHandler {

    private final ToolManagerClient toolManagerClient;
    private final ToolCatalogService toolCatalogService;
    private final ToolAuditService toolAuditService;
    private final HitlConfirmationService hitlConfirmationService;

    @Override
    public String type() {
        return WorkflowNodeType.TOOL.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String tool = readParamString(spec, "tool");
        Map<String, Object> invokeParams = resolveInvokeParams(spec.inputs(), ctx);
        if (invokeParams == null) {
            return Mono.just(NodeResult.fail("缺少必填参数"));
        }
        Map<String, String> displayParams = toStringParams(invokeParams);

        return Mono.fromCallable(() -> invokeWithHitl(spec, streamCtx, tool, invokeParams, displayParams))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> buildResult(tool, result))
                .doOnSuccess(result -> auditToolCall(spec, streamCtx, tool, displayParams, result))
                .onErrorResume(e -> {
                    if (e instanceof HitlWaitInterruptedException
                            || (e.getCause() instanceof HitlWaitInterruptedException)) {
                        return Mono.error(e);
                    }
                    log.warn("[ToolNodeHandler] 工具 {} 失败: {}", tool, e.getMessage());
                    auditToolFailure(spec, streamCtx, tool, displayParams, e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }

    private NodeResult buildResult(String tool, JsonNode result) {
        if (ToolManagerClient.isInvokeFailureResult(result)) {
            String err = result.has("error") ? result.get("error").asText() : "工具调用失败";
            return NodeResult.fail(err);
        }
        JsonNode payload = result != null ? result : NullNode.getInstance();
        Map<String, TypedValue> outputs = new LinkedHashMap<>();
        outputs.put("output", TypedValue.fromJson(payload));
        outputs.put("tool", TypedValue.scalar(tool));
        String text = renderForSummary(payload);
        String summary = summarizeToolOutput(tool, text);
        if (StringUtils.hasText(summary)) {
            outputs.put("summary", TypedValue.scalar(summary.strip()));
        }
        outputs.put("detail", TypedValue.scalar(summary));
        return NodeResult.ok(outputs);
    }

    /**
     * 按 {@link InputBinding} 解析显式输入；必填项缺失返回 {@code null}（节点失败）。
     * 无 inputs 时退化为空 params（兼容旧 YAML 直接写 params 的场景由调用方保证）。
     */
    private Map<String, Object> resolveInvokeParams(java.util.List<InputBinding> inputs, WorkflowContext ctx) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (inputs == null || inputs.isEmpty()) {
            return params;
        }
        for (InputBinding binding : inputs) {
            TypedValue val = TemplateResolver.resolveTyped(binding.source(), ctx);
            if (binding.required() && isNullTypedValue(val)) {
                return null;
            }
            params.put(binding.name(), val.toJson());
        }
        return params;
    }

    private static boolean isNullTypedValue(TypedValue val) {
        if (val == null) {
            return true;
        }
        JsonNode json = val.toJson();
        return json == null || json.isNull();
    }

    private JsonNode invokeWithHitl(
            NodeSpec spec,
            ExecutionStreamContext streamCtx,
            String tool,
            Map<String, Object> invokeParams,
            Map<String, String> displayParams) {
        WorkflowHitlScope.Binding hitl = streamCtx.workflowHitl();
        if (hitlConfirmationService != null
                && hitlConfirmationService.shouldConfirmWorkflow(tool, hitl)
                && !streamCtx.workflowHitlPreApproved()) {
            boolean approved = hitlConfirmationService.awaitWorkflowConfirmation(
                    hitl, streamCtx.assistantMsgId(), tool, displayParams);
            if (!approved) {
                return hitlConfirmationService.rejectionMessage() != null
                        ? ToolManagerClientFailure.toJson(hitlConfirmationService.rejectionMessage())
                        : ToolManagerClientFailure.toJson("HITL 拒绝");
            }
        }
        return toolManagerClient.invokeJsonMono(tool, invokeParams, streamCtx.userId(), streamCtx.tenantId()).block();
    }

    private void auditToolCall(
            NodeSpec spec,
            ExecutionStreamContext streamCtx,
            String tool,
            Map<String, String> params,
            NodeResult result) {
        if (result == null || !result.success()) {
            return;
        }
        TypedValue detailVal = result.safeOutputs().getOrDefault("detail", result.safeOutputs().get("output"));
        String summary = detailVal != null ? detailVal.render() : "";
        toolAuditService.toolCall(
                streamCtx.conversationId(),
                streamCtx.assistantMsgId(),
                streamCtx.userId(),
                streamCtx.tenantId(),
                streamCtx.persistedPlanId(),
                spec.id(),
                tool,
                params,
                summary,
                "ok");
    }

    private void auditToolFailure(
            NodeSpec spec,
            ExecutionStreamContext streamCtx,
            String tool,
            Map<String, String> params,
            String error) {
        toolAuditService.toolCall(
                streamCtx.conversationId(),
                streamCtx.assistantMsgId(),
                streamCtx.userId(),
                streamCtx.tenantId(),
                streamCtx.persistedPlanId(),
                spec.id(),
                tool,
                params,
                error,
                "failed");
    }

    private String summarizeToolOutput(String tool, String text) {
        return toolCatalogService.timelineSummary(tool, text);
    }

    /** 结构化 JsonNode 渲染为可读文本（供 timeline summary） */
    private static String renderForSummary(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject() && node.has("output") && node.size() == 1) {
            return node.get("output").asText();
        }
        return node.toString();
    }

    private static String readParamString(NodeSpec spec, String key) {
        if (spec.params() == null) {
            return "";
        }
        Object v = spec.params().get(key);
        return v != null ? v.toString() : "";
    }

    /** Map<String,Object> -> Map<String,String>（HITL/审计展示用，非文本值 JSON 序列化） */
    private static Map<String, String> toStringParams(Map<String, Object> params) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) {
            return out;
        }
        params.forEach((k, v) -> {
            if (k == null || v == null) {
                return;
            }
            out.put(k, v instanceof CharSequence ? v.toString() : v.toString());
        });
        return out;
    }

    /** HITL 拒绝等非工具返回的错误包装为含 error 字段的 JsonNode */
    private static final class ToolManagerClientFailure {
        private static final com.fasterxml.jackson.databind.ObjectMapper OM =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static JsonNode toJson(String message) {
            com.fasterxml.jackson.databind.node.ObjectNode node = OM.createObjectNode();
            node.put("error", message != null ? message : "工具调用失败");
            return node;
        }
    }
}
