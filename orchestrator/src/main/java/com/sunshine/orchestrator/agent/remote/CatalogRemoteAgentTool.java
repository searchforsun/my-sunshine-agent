package com.sunshine.orchestrator.agent.remote;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.audit.ToolAuditService;
import com.sunshine.orchestrator.catalog.ToolCatalogEntry;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.hitl.HitlWaitInterruptedException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用远程工具 — 基于 catalog 元数据实现 AgentTool，替代多个 *RemoteTool Bean
 */
@Slf4j
public class CatalogRemoteAgentTool implements AgentTool {

    private final ToolCatalogEntry entry;
    private final ToolManagerClient toolManagerClient;
    private final ToolAuditService toolAuditService;
    private final HitlConfirmationService hitlConfirmationService;

    public CatalogRemoteAgentTool(
            ToolCatalogEntry entry,
            ToolManagerClient toolManagerClient,
            ToolAuditService toolAuditService,
            HitlConfirmationService hitlConfirmationService) {
        this.entry = entry;
        this.toolManagerClient = toolManagerClient;
        this.toolAuditService = toolAuditService;
        this.hitlConfirmationService = hitlConfirmationService;
    }

    @Override
    public String getName() {
        return entry.id();
    }

    @Override
    public String getDescription() {
        return entry.description();
    }

    @Override
    public Map<String, Object> getParameters() {
        return entry.parameters() != null ? entry.parameters() : Map.of();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, String> invokeParams = new LinkedHashMap<>();
        Map<String, Object> input = param.getInput();
        if (input != null) {
            input.forEach((k, v) -> invokeParams.put(k, v != null ? String.valueOf(v) : ""));
        }
        return Mono.fromCallable(() -> executeWithHitl(param, invokeParams))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock executeWithHitl(ToolCallParam param, Map<String, String> invokeParams) {
        String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
        String bridgeId = StepEventBridge.bridgeIdForToolUse(toolUseId);
        if (bridgeId == null) {
            bridgeId = StepEventBridge.resolveHitlBridgeId();
        }
        if (hitlConfirmationService != null
                && hitlConfirmationService.shouldConfirmForBridge(entry.id(), bridgeId)) {
            String generationMessageId = StepEventBridge.hitlAssistantMessageId(bridgeId);
            String preApproveMsgId = generationMessageId != null ? generationMessageId : StepEventBridge.activeMessageId();
            if (preApproveMsgId != null
                    && StepEventBridge.consumeHitlPreApproval(preApproveMsgId, entry.id(), invokeParams)) {
                log.info("[CatalogRemoteAgentTool] {} 续跑 re-await 已确认，跳过二次 HITL", entry.id());
            } else {
                boolean approved = generationMessageId != null
                        ? hitlConfirmationService.awaitConfirmation(bridgeId, generationMessageId, entry.id(), invokeParams)
                        : hitlConfirmationService.awaitConfirmation(bridgeId, entry.id(), invokeParams);
                if (!approved) {
                    String skipBridgeId = bridgeId;
                    if (skipBridgeId != null) {
                        StepEventBridge.emit(skipBridgeId, session -> session.skipCurrentToolStep(
                                hitlConfirmationService.skippedAfterSummary()));
                        String flushId = generationMessageId != null ? generationMessageId : skipBridgeId;
                        hitlConfirmationService.flushTimeline(flushId);
                    }
                    String rejection = hitlConfirmationService.rejectionMessage();
                    auditIfBound(entry.id(), invokeParams, rejection, "skipped");
                    return ToolResultBlock.of(
                            toolUseId,
                            entry.id(),
                            TextBlock.builder().text(rejection).build());
                }
            }
        }
        log.info("[CatalogRemoteAgentTool] {} params={}", entry.id(), invokeParams);
        String result = toolManagerClient.invoke(entry.id(), invokeParams);
        auditIfBound(entry.id(), invokeParams, result, "ok");
        return ToolResultBlock.of(
                toolUseId,
                entry.id(),
                TextBlock.builder().text(result != null ? result : "").build());
    }

    private void auditIfBound(String toolId, Map<String, String> params, String output, String status) {
        String messageId = StepEventBridge.activeMessageId();
        StepEventBridge.ToolAuditContext ctx = StepEventBridge.toolAuditContext(messageId);
        if (ctx == null || toolAuditService == null) {
            return;
        }
        String summary = output != null && output.length() > 240 ? output.substring(0, 240) + "..." : output;
        toolAuditService.toolCall(
                ctx.conversationId(),
                ctx.messageId(),
                ctx.userId(),
                ctx.tenantId(),
                ctx.planId(),
                null,
                toolId,
                params,
                summary != null ? summary : "",
                status);
    }
}
