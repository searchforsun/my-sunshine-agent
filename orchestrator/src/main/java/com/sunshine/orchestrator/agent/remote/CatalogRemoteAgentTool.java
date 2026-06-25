package com.sunshine.orchestrator.agent.remote;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.audit.ToolAuditService;
import com.sunshine.orchestrator.catalog.ToolCatalogEntry;
import com.sunshine.orchestrator.client.ToolManagerClient;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

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

    public CatalogRemoteAgentTool(
            ToolCatalogEntry entry,
            ToolManagerClient toolManagerClient,
            ToolAuditService toolAuditService) {
        this.entry = entry;
        this.toolManagerClient = toolManagerClient;
        this.toolAuditService = toolAuditService;
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
        log.info("[CatalogRemoteAgentTool] {} params={}", entry.id(), invokeParams);
        String result = toolManagerClient.invoke(entry.id(), invokeParams);
        auditIfBound(entry.id(), invokeParams, result, "ok");
        String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
        return Mono.just(ToolResultBlock.of(
                toolUseId,
                entry.id(),
                TextBlock.builder().text(result != null ? result : "").build()));
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
