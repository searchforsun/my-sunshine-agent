package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 MySQL ToolSet + Catalog 启用池动态组装 Toolkit
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicToolkitFactory {

    private static final String DEFAULT_TENANT = "default";

    private final RagTool ragTool;
    private final ManageTasksTool manageTasksTool;
    private final GenericRemoteToolFactory remoteToolFactory;
    private final ToolCatalogService toolCatalogService;
    private final ToolSetResolver toolSetResolver;
    private final AgentExecutionProperties executionProperties;

    /** 主 Agent：租户 ReAct 默认工具集 */
    public Toolkit build() {
        return build(DEFAULT_TENANT);
    }

    public Toolkit build(String tenantId) {
        return buildFromWhitelist(toolSetResolver.resolveReactTools(tenantId));
    }

    /** 子 Agent：显式白名单与启用池求交 */
    public Toolkit build(List<String> toolWhitelist) {
        return build(toolWhitelist, DEFAULT_TENANT);
    }

    public Toolkit build(List<String> toolWhitelist, String tenantId) {
        if (toolWhitelist == null || toolWhitelist.isEmpty()) {
            return build(tenantId);
        }
        return buildFromWhitelist(toolSetResolver.intersectEnabledPool(toolWhitelist, tenantId));
    }

    private Toolkit buildFromWhitelist(List<String> whitelist) {
        Toolkit tk = new Toolkit();
        List<String> registered = new ArrayList<>();
        Set<String> registeredRemote = new HashSet<>();
        List<String> missing = new ArrayList<>();

        for (String toolName : whitelist) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            if (toolName.equals(ManageTasksTool.NAME)) {
                log.warn("[Orchestrator] manage_tasks 为内置元工具，勿放入 ReAct 工具集");
                continue;
            }
            if (toolCatalogService.isRagTool(toolName)) {
                tk.registerAgentTool(ragTool);
                registered.add(toolName);
                continue;
            }
            remoteToolFactory.create(toolName).ifPresentOrElse(agentTool -> {
                if (registeredRemote.add(agentTool.getName())) {
                    tk.registerAgentTool(agentTool);
                }
                registered.add(toolName);
            }, () -> missing.add(toolName));
        }

        AgentExecutionProperties.React react = executionProperties.getReact();
        if (react != null && react.getTaskboard() != null && react.getTaskboard().isEnabled()) {
            tk.registerTool(manageTasksTool);
            registered.add(ManageTasksTool.NAME);
        }

        if (!missing.isEmpty()) {
            log.error("[Orchestrator] ReAct 工具集条目未在 Catalog 注册: {}", missing);
        }

        log.info("[Orchestrator] DynamicToolkit 已注册工具: {}", String.join(", ", registered));
        return tk;
    }
}
