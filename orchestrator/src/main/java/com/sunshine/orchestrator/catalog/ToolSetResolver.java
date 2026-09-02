package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.ToolManagerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/** 按会话 kind（chat|task）解析默认工具集，并与 Catalog 启用池求交 */
@Component
@RequiredArgsConstructor
public class ToolSetResolver {

    private final ToolManagerClient toolManagerClient;
    private final ToolCatalogService toolCatalogService;

    /** chat 会话默认工具集 */
    public List<String> resolveChatTools(String tenantId) {
        String effectiveTenant = normalizeTenant(tenantId);
        List<String> setIds = toolManagerClient.fetchChatDefault(effectiveTenant);
        Set<String> pool = toolCatalogService.enabledIds(effectiveTenant);
        return setIds.stream().filter(pool::contains).toList();
    }

    /** task 会话默认工具集 */
    public List<String> resolveTaskTools(String tenantId) {
        String effectiveTenant = normalizeTenant(tenantId);
        List<String> setIds = toolManagerClient.fetchTaskDefault(effectiveTenant);
        Set<String> pool = toolCatalogService.enabledIds(effectiveTenant);
        return setIds.stream().filter(pool::contains).toList();
    }

    /**
     * 按会话 kind 装默认工具集：task→task 集，其余（含 null/blank）→chat 集。
     * 不按 executionMode 分支。
     */
    public List<String> resolveDefaultTools(String tenantId, String conversationKind) {
        if ("task".equals(normalizeKind(conversationKind))) {
            return resolveTaskTools(tenantId);
        }
        return resolveChatTools(tenantId);
    }

    /** 租户启用 Catalog 全量（专家 tools_json=["*"] 过渡语义） */
    public List<String> resolveAllEnabledTools(String tenantId) {
        return List.copyOf(toolCatalogService.enabledIds(normalizeTenant(tenantId)));
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

    /**
     * 委派工具召集双轨求交（skill-sticky A-1/A-6）：(tenant, kind) 默认工具集 ∩ 声明/自选工具。
     * 预定义 agent 自动注入与动态 sub agent 的 {@code tool_ids} 均走此约束；
     * 越界声明运行时剔除（防御工具集变更，可用性唯一控制点 = 工具集配置）。
     */
    public List<String> intersectToolSet(List<String> toolIds, String tenantId, String conversationKind) {
        if (toolIds == null || toolIds.isEmpty()) {
            return List.of();
        }
        Set<String> toolSet = Set.copyOf(resolveDefaultTools(tenantId, conversationKind));
        return toolIds.stream()
                .filter(id -> id != null && !id.isBlank() && toolSet.contains(id.strip()))
                .map(String::strip)
                .toList();
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
    }

    /** 缺省 chat */
    static String normalizeKind(String conversationKind) {
        return StringUtils.hasText(conversationKind) ? conversationKind.strip() : "chat";
    }
}
