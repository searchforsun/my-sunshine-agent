package com.sunshine.tool.registry;

import com.sunshine.tool.invoke.InvokeRouter;
import com.sunshine.tool.repo.ToolDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具调用与摘要策略入口 — Catalog 由 DbToolCatalogService 提供
 */
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final InvokeRouter invokeRouter;
    private final ToolDefinitionRepository toolDefinitionRepository;

    public String invoke(String name, Map<String, String> params, String tenantId) {
        return invokeRouter.invoke(name, params, tenantId);
    }

    public String outputSummaryKind(String name) {
        return toolDefinitionRepository.findById(name)
                .map(com.sunshine.tool.entity.ToolDefinitionEntity::getOutputSummaryKind)
                .orElse("truncate");
    }
}
