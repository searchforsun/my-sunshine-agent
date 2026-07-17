package com.sunshine.tool.registry;

import com.sunshine.tool.invoke.InvokeRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 工具调用入口 — Catalog 由 DbToolCatalogService 提供 */
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final InvokeRouter invokeRouter;

    public String invoke(String name, Map<String, String> params, String tenantId) {
        return invokeRouter.invoke(name, params, tenantId);
    }
}
