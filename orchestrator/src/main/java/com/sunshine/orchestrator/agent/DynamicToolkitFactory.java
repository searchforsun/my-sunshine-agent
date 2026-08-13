package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.sandbox.SandboxAgentTools;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 MySQL ToolSet + Catalog 启用池动态组装 Toolkit。
 * ReAct 路径均硬编码注入 {@link RagTool}（直连 Gateway / DIRECT 不经本工厂）；Workflow 子 Agent 亦始终含 RAG，另可加节点 tools 白名单。
 * MAIN / SUB 均注入沙箱六工具（方案 B + SUB 默认沙箱）；SUB 不注入 spawn_subagent / request_decision；任务板（原生 todo_write）仅 MAIN 由 enableTaskList 注册。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicToolkitFactory {

    private static final String DEFAULT_TENANT = "default";

    private final RagTool ragTool;
    private final SpawnSubagentTool spawnSubagentTool;
    private final RequestDecisionTool requestDecisionTool;
    private final AwaitToolRunTool awaitToolRunTool;
    private final ThinkSummaryTool thinkSummaryTool;
    private final GenericRemoteToolFactory remoteToolFactory;
    private final ToolCatalogService toolCatalogService;
    private final ToolSetResolver toolSetResolver;
    private final AgentExecutionProperties executionProperties;
    private final SandboxAgentTools sandboxAgentTools;

    /** 主 Agent：按会话 kind 装默认工具集（缺省 chat） */
    public Toolkit build(String userId) {
        return build(DEFAULT_TENANT, null, userId, null);
    }

    public Toolkit build(String tenantId, String skillId, String userId) {
        return build(tenantId, skillId, userId, null);
    }

    public Toolkit build(String tenantId, String skillId, String userId, String conversationKind) {
        return buildFromWhitelist(
                toolSetResolver.resolveDefaultTools(tenantId, conversationKind),
                ToolkitScope.MAIN, skillId, userId, tenantId);
    }

    /** 子 Agent / 白名单：显式白名单与启用池求交 */
    public Toolkit build(List<String> toolWhitelist, String tenantId, String skillId, String userId) {
        return build(toolWhitelist, tenantId, skillId, userId, null);
    }

    public Toolkit build(
            List<String> toolWhitelist, String tenantId, String skillId, String userId, String conversationKind) {
        if (toolWhitelist == null || toolWhitelist.isEmpty()) {
            return build(tenantId, skillId, userId, conversationKind);
        }
        return buildFromWhitelist(
                toolSetResolver.intersectEnabledPool(toolWhitelist, tenantId),
                ToolkitScope.MAIN, skillId, userId, tenantId);
    }

    /** Workflow 子 Agent：始终含 search_knowledge；可选 tools 白名单追加业务工具（不含 spawn_subagent / request_decision） */
    public Toolkit buildForSubAgent(List<String> toolWhitelist, String tenantId, String skillId, String userId) {
        List<String> whitelist = toolWhitelist == null || toolWhitelist.isEmpty()
                ? List.of()
                : toolSetResolver.intersectEnabledPool(toolWhitelist, tenantId);
        return buildFromWhitelist(whitelist, ToolkitScope.SUB, skillId, userId, tenantId);
    }

    /**
     * Planner-Executor：业务工具 + RAG/沙箱/await；<b>不</b>注册 spawn_subagent（Worker 内 spawn）
     * 与 request_decision（D12 延后）。{@code dispatch_worker} 由 {@link ReActAgentFactory} 注册。
     * 按会话 kind 装集（缺省 chat）。
     */
    public Toolkit buildForPlanner(String tenantId, String skillId, String userId) {
        return buildForPlanner(tenantId, skillId, userId, null);
    }

    public Toolkit buildForPlanner(String tenantId, String skillId, String userId, String conversationKind) {
        return buildFromWhitelist(
                toolSetResolver.resolveDefaultTools(tenantId, conversationKind),
                ToolkitScope.PLANNER, skillId, userId, tenantId);
    }

    private enum ToolkitScope {
        MAIN, SUB, PLANNER
    }

    private Toolkit buildFromWhitelist(
            List<String> whitelist, ToolkitScope scope, String skillId, String userId, String tenantId) {
        // skillId 保留签名兼容；方案 B 不再门控沙箱工具
        // 同轮无依赖 tool_call 并行（须 AgentScope ≥1.0.8：mergeSequential 保序，避免结果错配）
        Toolkit tk = new Toolkit(ToolkitConfig.builder().parallel(true).build());
        List<String> registered = new ArrayList<>();
        Set<String> registeredRemote = new HashSet<>();
        List<String> missing = new ArrayList<>();

        if (scope == ToolkitScope.MAIN || scope == ToolkitScope.SUB || scope == ToolkitScope.PLANNER) {
            tk.registerAgentTool(ragTool);
            registered.add(RagTool.NAME);
        }

        for (String toolName : whitelist) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            if (toolName.equals(SpawnSubagentTool.NAME)) {
                log.warn("[Orchestrator] spawn_subagent 为内置元工具，勿放入 ReAct 工具集");
                continue;
            }
            if (toolName.equals(RequestDecisionTool.NAME)) {
                log.warn("[Orchestrator] request_decision 为内置元工具，勿放入 ReAct 工具集");
                continue;
            }
            if (toolName.equals(AwaitToolRunTool.NAME)) {
                log.warn("[Orchestrator] await_tool_run 为内置元工具，勿放入 ReAct 工具集");
                continue;
            }
            if (toolName.equals(RagTool.NAME)) {
                continue;
            }
            if (toolCatalogService.isRagTool(toolName)) {
                log.warn("[Orchestrator] 非内置 RAG 工具 {} 已忽略，请使用 {}", toolName, RagTool.NAME);
                continue;
            }
            remoteToolFactory.create(toolName, userId, tenantId).ifPresentOrElse(agentTool -> {
                if (registeredRemote.add(agentTool.getName())) {
                    tk.registerAgentTool(agentTool);
                }
                registered.add(toolName);
            }, () -> missing.add(toolName));
        }

        if (scope == ToolkitScope.MAIN || scope == ToolkitScope.SUB || scope == ToolkitScope.PLANNER) {
            tk.registerTool(thinkSummaryTool);
            registered.add(ThinkSummaryTool.NAME);
        }
        if (scope == ToolkitScope.MAIN || scope == ToolkitScope.PLANNER) {
            AgentExecutionProperties.React react = executionProperties.getReact();
            // spawn_subagent 仅 MAIN：Planner 经 dispatch_worker → Worker，Worker/MAIN 内再 spawn
            if (scope == ToolkitScope.MAIN
                    && react != null && react.getSubagent() != null && react.getSubagent().isEnabled()) {
                tk.registerAgentTool(spawnSubagentTool);
                registered.add(SpawnSubagentTool.NAME);
            }
            // D12：PLANNER 即使 decision.enabled 也不注册 request_decision
            if (react != null && react.getDecision() != null && react.getDecision().isEnabled()
                    && scope == ToolkitScope.MAIN) {
                tk.registerAgentTool(requestDecisionTool);
                registered.add(RequestDecisionTool.NAME);
            }
            if (react != null && react.getAsyncTool() != null && react.getAsyncTool().isEnabled()) {
                tk.registerAgentTool(awaitToolRunTool);
                registered.add(AwaitToolRunTool.NAME);
            }
        }
        if (scope == ToolkitScope.MAIN || scope == ToolkitScope.SUB || scope == ToolkitScope.PLANNER) {
            for (AgentTool t : sandboxAgentTools.all()) {
                tk.registerAgentTool(t);
                registered.add(t.getName());
            }
        }

        if (!missing.isEmpty()) {
            log.error("[Orchestrator] ReAct 工具集条目未在 Catalog 注册: {}", missing);
        }
        if (scope == ToolkitScope.MAIN && whitelist.isEmpty()) {
            log.error("[Orchestrator] ReAct 工具集白名单为空（仅内置 RAG/沙箱/元工具）；"
                    + "若伴随 ToolManagerClient non-blocking 报错，检查是否在 reactor-http 线程 block");
        }

        log.info("[Orchestrator] DynamicToolkit 已注册工具: {}", String.join(", ", registered));
        return tk;
    }
}
