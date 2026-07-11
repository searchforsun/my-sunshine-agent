package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.ToolSetClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** 解析租户 ReAct 工具集并与 Catalog 启用池求交 */
@Component
@RequiredArgsConstructor
public class ToolSetResolver {

    private final ToolSetClient toolSetClient;
    private final ToolCatalogService toolCatalogService;

    public List<String> resolveReactTools(String tenantId) {
        String effectiveTenant = normalizeTenant(tenantId);
        List<String> setIds = toolSetClient.fetchReactDefault(effectiveTenant);
        Set<String> pool = toolCatalogService.enabledIds(effectiveTenant);
        return setIds.stream().filter(pool::contains).toList();
    }

    /** Plan-Workflow 可用工具（与启用池求交） */
    public List<String> resolvePlanWorkflowTools(String tenantId) {
        String effectiveTenant = normalizeTenant(tenantId);
        List<String> setIds = toolSetClient.fetchPlanWorkflow(effectiveTenant);
        Set<String> pool = toolCatalogService.enabledIds(effectiveTenant);
        return setIds.stream().filter(pool::contains).toList();
    }

    /** Plan/Workflow 关键工具（失败时 fail_fast），与启用池求交 */
    public List<String> resolvePlanWorkflowCriticalTools(String tenantId) {
        String effectiveTenant = normalizeTenant(tenantId);
        List<String> setIds = toolSetClient.fetchPlanWorkflowCritical(effectiveTenant);
        Set<String> pool = toolCatalogService.enabledIds(effectiveTenant);
        return setIds.stream().filter(pool::contains).toList();
    }

    /** 显式白名单与启用池求交（子 Agent / 节点 tools） */
    public List<String> intersectEnabledPool(List<String> toolIds, String tenantId) {
        if (toolIds == null || toolIds.isEmpty()) {
            return List.of();
        }
        Set<String> pool = toolCatalogService.enabledIds(normalizeTenant(tenantId));
        return toolIds.stream()
                .filter(id -> id != null && !id.isBlank() && pool.contains(id.strip()))
                .map(String::strip)
                .toList();
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
    }
}
