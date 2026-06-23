package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
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
 * 按 Nacos react 工具白名单 + catalog 动态组装 Toolkit
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicToolkitFactory {

    private final RagTool ragTool;
    private final GenericRemoteToolFactory remoteToolFactory;
    private final ToolCatalogService toolCatalogService;
    private final AgentExecutionProperties executionProperties;

    /** 主 Agent：Nacos react 全局白名单 */
    public Toolkit build() {
        List<String> whitelist = executionProperties.getReact() != null
                ? executionProperties.getReact().getTools()
                : List.of();
        return buildFromWhitelist(whitelist);
    }

    /** 子 Agent：仅注册节点/skill 指定的工具子集 */
    public Toolkit build(List<String> toolWhitelist) {
        if (toolWhitelist == null || toolWhitelist.isEmpty()) {
            return build();
        }
        return buildFromWhitelist(toolWhitelist);
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
            if (toolCatalogService.isRagTool(toolName)) {
                tk.registerTool(ragTool);
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

        if (!missing.isEmpty()) {
            log.error("[Orchestrator] react 白名单工具未在 Catalog 注册: {}", missing);
        }

        log.info("[Orchestrator] DynamicToolkit 已注册工具: {}", String.join(", ", registered));
        return tk;
    }
}
