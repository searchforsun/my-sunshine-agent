package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.sandbox.SandboxAgentTools;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 MySQL ToolSet + Catalog 启用池动态组装 Toolkit。
 * 非 simple-llm 的 ReAct 路径均硬编码注入 {@link RagTool}；Workflow 子 Agent 亦始终含 RAG，另可加节点 tools 白名单。
 * Skill {@code sandbox != none} 时追加沙箱六工具（不进 Catalog）。
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
    private final SkillCatalogService skillCatalogService;
    private final SandboxAgentTools sandboxAgentTools;

    /** 主 Agent：租户 ReAct 默认工具集 */
    public Toolkit build() {
        return build(DEFAULT_TENANT);
    }

    public Toolkit build(String tenantId) {
        return build(tenantId, null);
    }

    public Toolkit build(String tenantId, String skillId) {
        return buildFromWhitelist(toolSetResolver.resolveReactTools(tenantId), ToolkitScope.MAIN, skillId);
    }

    /** 子 Agent：显式白名单与启用池求交 */
    public Toolkit build(List<String> toolWhitelist) {
        return build(toolWhitelist, DEFAULT_TENANT);
    }

    public Toolkit build(List<String> toolWhitelist, String tenantId) {
        return build(toolWhitelist, tenantId, null);
    }

    public Toolkit build(List<String> toolWhitelist, String tenantId, String skillId) {
        if (toolWhitelist == null || toolWhitelist.isEmpty()) {
            return build(tenantId, skillId);
        }
        return buildFromWhitelist(
                toolSetResolver.intersectEnabledPool(toolWhitelist, tenantId), ToolkitScope.MAIN, skillId);
    }

    /** Workflow 子 Agent：始终含 search_knowledge；可选 tools 白名单追加业务工具（不含 manage_tasks） */
    public Toolkit buildForSubAgent(List<String> toolWhitelist, String tenantId) {
        return buildForSubAgent(toolWhitelist, tenantId, null);
    }

    public Toolkit buildForSubAgent(List<String> toolWhitelist, String tenantId, String skillId) {
        List<String> whitelist = toolWhitelist == null || toolWhitelist.isEmpty()
                ? List.of()
                : toolSetResolver.intersectEnabledPool(toolWhitelist, tenantId);
        return buildFromWhitelist(whitelist, ToolkitScope.SUB, skillId);
    }

    private enum ToolkitScope {
        MAIN, SUB
    }

    private Toolkit buildFromWhitelist(List<String> whitelist, ToolkitScope scope, String skillId) {
        Toolkit tk = new Toolkit();
        List<String> registered = new ArrayList<>();
        Set<String> registeredRemote = new HashSet<>();
        List<String> missing = new ArrayList<>();

        if (scope == ToolkitScope.MAIN || scope == ToolkitScope.SUB) {
            tk.registerAgentTool(ragTool);
            registered.add(RagTool.NAME);
        }

        for (String toolName : whitelist) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            if (toolName.equals(ManageTasksTool.NAME)) {
                log.warn("[Orchestrator] manage_tasks 为内置元工具，勿放入 ReAct 工具集");
                continue;
            }
            if (toolName.equals(RagTool.NAME)) {
                continue;
            }
            if (toolCatalogService.isRagTool(toolName)) {
                log.warn("[Orchestrator] 非内置 RAG 工具 {} 已忽略，请使用 {}", toolName, RagTool.NAME);
                continue;
            }
            remoteToolFactory.create(toolName).ifPresentOrElse(agentTool -> {
                if (registeredRemote.add(agentTool.getName())) {
                    tk.registerAgentTool(agentTool);
                }
                registered.add(toolName);
            }, () -> missing.add(toolName));
        }

        if (scope == ToolkitScope.MAIN) {
            AgentExecutionProperties.React react = executionProperties.getReact();
            if (react != null && react.getTaskboard() != null && react.getTaskboard().isEnabled()) {
                tk.registerTool(manageTasksTool);
                registered.add(ManageTasksTool.NAME);
            }
        }

        if (shouldAttachSandbox(skillId)) {
            for (AgentTool t : sandboxAgentTools.all()) {
                tk.registerAgentTool(t);
                registered.add(t.getName());
            }
        }

        if (!missing.isEmpty()) {
            log.error("[Orchestrator] ReAct 工具集条目未在 Catalog 注册: {}", missing);
        }

        log.info("[Orchestrator] DynamicToolkit 已注册工具: {}", String.join(", ", registered));
        return tk;
    }

    /** Skill 声明 sandbox 且不为 none 时注入六工具；无 skillId → 不注入 */
    boolean shouldAttachSandbox(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return false;
        }
        return skillCatalogService.find(skillId.strip())
                .map(e -> e.sandbox() != null && !"none".equalsIgnoreCase(e.sandbox().strip()))
                .orElse(false);
    }
}
