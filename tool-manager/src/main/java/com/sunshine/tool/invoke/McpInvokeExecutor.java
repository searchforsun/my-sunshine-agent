package com.sunshine.tool.invoke;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.config.ToolIntegrationProperties;
import com.sunshine.tool.entity.McpServerEntity;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.mcp.McpJsonRpcClient;
import com.sunshine.tool.repo.McpServerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpInvokeExecutor {

    private final McpServerRepository mcpServerRepository;
    private final McpJsonRpcClient mcpJsonRpcClient;
    private final ToolIntegrationProperties properties;

    public String invoke(ToolDefinitionEntity tool, Map<String, String> params) {
        McpServerEntity server = mcpServerRepository.findById(tool.getSourceRef())
                .orElseThrow(() -> new BizException(ToolErrorCode.MCP_SERVER_NOT_FOUND));
        if (!server.isEnabled()) {
            throw new BizException(ToolErrorCode.MCP_SERVER_DISABLED);
        }
        Duration timeout = Duration.ofSeconds(Math.max(5, properties.getMcp().getInvokeTimeoutSeconds()));
        try {
            return mcpJsonRpcClient.toolsCall(server, tool.getExternalName(), params, timeout);
        } catch (Exception e) {
            log.warn("[McpInvokeExecutor] invoke failed tool={}: {}", tool.getId(), e.getMessage());
            throw new BizException(ToolErrorCode.MCP_INVOKE_FAILED);
        }
    }
}
