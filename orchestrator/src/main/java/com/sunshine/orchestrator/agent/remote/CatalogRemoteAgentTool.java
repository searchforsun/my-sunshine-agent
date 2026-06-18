package com.sunshine.orchestrator.agent.remote;

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

    public CatalogRemoteAgentTool(ToolCatalogEntry entry, ToolManagerClient toolManagerClient) {
        this.entry = entry;
        this.toolManagerClient = toolManagerClient;
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
        String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
        return Mono.just(ToolResultBlock.of(
                toolUseId,
                entry.id(),
                TextBlock.builder().text(result != null ? result : "").build()));
    }
}
