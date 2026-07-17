package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.audit.ToolAuditService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
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
import java.util.Set;

/**
 * 工具调用节点 — 委托 tool-manager，params 统一透传；写工具走 HITL（streamCtx.workflowHitl）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolNodeHandler implements NodeHandler {

    private static final Set<String> RESERVED_INVOKE_KEYS = Set.of(
            "tool", "output.mode", "output.extract");

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
        String tool = spec.params().getOrDefault("tool", "");
        Map<String, String> invokeParams = new LinkedHashMap<>();
        spec.params().forEach((k, v) -> {
            if (!RESERVED_INVOKE_KEYS.contains(k)) {
                invokeParams.put(k, v);
            }
        });

        return Mono.fromCallable(() -> invokeWithHitl(spec, streamCtx, tool, invokeParams))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    if (ToolManagerClient.isInvokeFailureResult(result)) {
                        return NodeResult.fail(result);
                    }
                    String text = result != null ? result : "";
                    Map<String, String> outputs = new LinkedHashMap<>();
                    outputs.put("output", text);
                    outputs.put("tool", tool);
                    String summary = summarizeToolOutput(tool, text);
                    if (StringUtils.hasText(summary)) {
                        outputs.put("summary", summary.strip());
                    }
                    outputs.put("detail", summary);
                    appendParsedOutputs(outputs, spec.params(), text);
                    return NodeResult.ok(outputs);
                })
                .doOnSuccess(result -> auditToolCall(spec, streamCtx, tool, invokeParams, result))
                .onErrorResume(e -> {
                    if (e instanceof HitlWaitInterruptedException
                            || (e.getCause() instanceof HitlWaitInterruptedException)) {
                        return Mono.error(e);
                    }
                    log.warn("[ToolNodeHandler] 工具 {} 失败: {}", tool, e.getMessage());
                    auditToolFailure(spec, streamCtx, tool, invokeParams, e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }

    private String invokeWithHitl(
            NodeSpec spec,
            ExecutionStreamContext streamCtx,
            String tool,
            Map<String, String> invokeParams) {
        WorkflowHitlScope.Binding hitl = streamCtx.workflowHitl();
        if (hitlConfirmationService != null
                && hitlConfirmationService.shouldConfirmWorkflow(tool, hitl)
                && !streamCtx.workflowHitlPreApproved()) {
            boolean approved = hitlConfirmationService.awaitWorkflowConfirmation(
                    hitl, streamCtx.assistantMsgId(), tool, invokeParams);
            if (!approved) {
                return hitlConfirmationService.rejectionMessage();
            }
        }
        return toolManagerClient.invokeMono(tool, invokeParams).block();
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
        String summary = result.safeOutputs().getOrDefault("detail", result.safeOutputs().getOrDefault("output", ""));
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

    private void appendParsedOutputs(Map<String, String> outputs, Map<String, String> params, String text) {
        if (params == null || !"extract".equals(params.get("output.mode"))) {
            return;
        }
        String extractJson = params.get("output.extract");
        if (!StringUtils.hasText(extractJson)) {
            return;
        }
        Map<String, String> parsed = toolManagerClient.extractBindingsMono(extractJson, text)
                .blockOptional()
                .orElse(Map.of());
        parsed.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                outputs.put("parsed." + key.strip(), value);
            }
        });
    }
}
