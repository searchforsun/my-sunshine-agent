package com.sunshine.orchestrator.agent.remote;

import com.sunshine.orchestrator.catalog.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolManagerClient;
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

    public Optional<CatalogRemoteAgentTool> create(String toolName) {
        return toolCatalogService.find(toolName)
                .filter(entry -> "remote".equals(entry.kind()))
                .map(entry -> new CatalogRemoteAgentTool(entry, toolManagerClient));
    }

    public CatalogRemoteAgentTool createRequired(ToolCatalogEntry entry) {
        return new CatalogRemoteAgentTool(entry, toolManagerClient);
    }
}
