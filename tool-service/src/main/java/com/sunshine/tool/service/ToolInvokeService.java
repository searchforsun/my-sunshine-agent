package com.sunshine.tool.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.invoke.InvokeRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ToolInvokeService {

    private final InvokeRouter invokeRouter;

    public String invoke(String name, Map<String, String> params, String userId, String tenantId) {
        if (name == null || name.isBlank()) {
            throw new BizException(ToolErrorCode.TOOL_NAME_REQUIRED);
        }
        return invokeRouter.invoke(name.strip(), params, userId, tenantId);
    }
}
