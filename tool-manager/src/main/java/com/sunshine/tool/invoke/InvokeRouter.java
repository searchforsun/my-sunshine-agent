package com.sunshine.tool.invoke;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.tool.ToolIds;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class InvokeRouter {

    private final ToolDefinitionRepository toolDefinitionRepository;
    private final SdkInvokeExecutor sdkInvokeExecutor;
    private final McpInvokeExecutor mcpInvokeExecutor;

    public String invoke(String toolId, Map<String, String> params, String tenantId) {
        if (!ToolIds.isValid(toolId)) {
            throw new BizException(ToolErrorCode.TOOL_ID_INVALID);
        }
        ToolDefinitionEntity tool = requireEnabled(toolId, tenantId);
        return switch (tool.getSource()) {
            case "sdk" -> sdkInvokeExecutor.invoke(tool, params);
            case "mcp" -> mcpInvokeExecutor.invoke(tool, params);
            default -> throw new BizException(ToolErrorCode.UNSUPPORTED_SOURCE);
        };
    }

    private ToolDefinitionEntity requireEnabled(String toolId, String tenantId) {
        String effectiveTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
        ToolDefinitionEntity tool = toolDefinitionRepository.findVisibleById(toolId, effectiveTenant)
                .orElseThrow(() -> new BizException(ToolErrorCode.UNKNOWN_TOOL));
        if (!tool.isEnabled()) {
            throw new BizException(ToolErrorCode.TOOL_DISABLED);
        }
        if (!tool.isIdValid()) {
            throw new BizException(ToolErrorCode.TOOL_ID_INVALID);
        }
        return tool;
    }
}
