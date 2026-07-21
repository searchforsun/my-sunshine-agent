package com.sunshine.orchestrator.agent.remote;

import com.sunshine.orchestrator.audit.ToolAuditService;
import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 按 catalog 元数据创建 CatalogRemoteAgentTool 实例
 */
@Component
@RequiredArgsConstructor
public class GenericRemoteToolFactory {

    private final ToolCatalogService toolCatalogService;
    private final ToolManagerClient toolManagerClient;
    private final ToolAuditService toolAuditService;
    private final HitlConfirmationService hitlConfirmationService;

    public Optional<CatalogRemoteAgentTool> create(String toolName, String userId, String tenantId) {
        return toolCatalogService.find(toolName)
                .filter(entry -> "remote".equals(entry.kind()) || "mcp".equals(entry.kind()))
                .map(entry -> new CatalogRemoteAgentTool(
                        entry, toolManagerClient, toolAuditService, hitlConfirmationService, userId, tenantId));
    }

    public CatalogRemoteAgentTool createRequired(ToolCatalogEntry entry, String userId, String tenantId) {
        return new CatalogRemoteAgentTool(
                entry, toolManagerClient, toolAuditService, hitlConfirmationService, userId, tenantId);
    }
}
