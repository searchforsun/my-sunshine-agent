package com.sunshine.tool.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.registry.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ToolInvokeService {

    private final ToolRegistry toolRegistry;

    public String invoke(String name, Map<String, String> params) {
        if (name == null || name.isBlank()) {
            throw new BizException(ToolErrorCode.TOOL_NAME_REQUIRED);
        }
        return toolRegistry.invoke(name, params);
    }
}
