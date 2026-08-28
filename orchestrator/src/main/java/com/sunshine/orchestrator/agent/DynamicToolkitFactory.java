package com.sunshine.orchestrator.agent;

import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.orchestrator.catalog.AgentToolsJson;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
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
import org.springframework.util.StringUtils;

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
    private final AsyncStatusTool asyncStatusTool;
    private final ThinkSummaryTool thinkSummaryTool;
    private final GenericRemoteToolFactory remoteToolFactory;
    private final ToolCatalogService toolCatalogService;
    private final ToolSetResolver toolSetResolver;
    private final SkillCatalogService skillCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final SandboxAgentTools sandboxAgentTools;
    private final SessionSearchTool sessionSearchTool;
    private final SkillSearchTool skillSearchTool;
    private final ToolRetrievalService toolRetrievalService;

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
                ToolkitScope.MAIN, skillId, userId, tenantId, conversationKind);
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
                ToolkitScope.MAIN, skillId, userId, tenantId, conversationKind);
    }

    /** Workflow 子 Agent：始终含 search_knowledge；可选 tools 白名单追加业务工具（不含 spawn_subagent / request_decision） */
    public Toolkit buildForSubAgent(List<String> toolWhitelist, String tenantId, String skillId, String userId) {
        List<String> whitelist = toolWhitelist == null || toolWhitelist.isEmpty()
                ? List.of()
                : toolSetResolver.intersectEnabledPool(toolWhitelist, tenantId);
        return buildFromWhitelist(whitelist, ToolkitScope.SUB, skillId, userId, tenantId, null);
    }

    /**
     * Planner-Executor Worker：SUB 基础（RAG + 业务白名单 + think_summary + 沙箱）之上，
     * 额外注册异步元工具——await_tool_run（等待自己派发的 background exec / spawn run）、
     * async_status（run 级状态回查）、spawn_subagent（完整 fast ReAct，可隔离子工作）。
     * 不注册 request_decision（用户决策仍归主链）。session_search 二期跟进（需 Worker 独立工具审计上下文）。
     */
    public Toolkit buildForWorker(List<String> toolWhitelist, String tenantId, String skillId, String userId) {
        List<String> whitelist = toolWhitelist == null || toolWhitelist.isEmpty()
                ? List.of()
                : toolSetResolver.intersectEnabledPool(toolWhitelist, tenantId);
        return buildFromWhitelist(whitelist, ToolkitScope.WORKER, skillId, userId, tenantId, null);
    }

    /**
     * Planner-Executor：动作工具（plan_submit / self_assess）+ dispatch_worker + think_summary + await_tool_run；
     * <b>不</b>注册 RAG、沙箱、业务工具（Planner 只做规划调度，不执行内容）。
     * await_tool_run 必须注册：dispatch_worker 强制异步，Planner 经 await_tool_run 收集 Worker handoff；
     * 缺注册时模型调用失败会空转（曾出现幻觉工具名 wait_async_results）。
     * 动作/调度工具由 {@link ReActAgentFactory} 注册。
     */
    public Toolkit buildForPlanner(String tenantId, String skillId, String userId) {
        return buildForPlanner(tenantId, skillId, userId, null);
    }

    public Toolkit buildForPlanner(String tenantId, String skillId, String userId, String conversationKind) {
        // Planner 仅需 think_summary + await_tool_run + async_status + request_decision；业务工具/RAG/沙箱均不注入
        Toolkit tk = new Toolkit(ToolkitConfig.builder().parallel(true).build());
        tk.registerTool(thinkSummaryTool);
        AgentExecutionProperties.React react = executionProperties.getReact();
        // D12：Planner MAIN 与 Chat MAIN 同契约——decision.enabled 下注册 request_decision（需求歧义/方案抉择）
        if (react != null && react.getDecision() != null && react.getDecision().isEnabled()) {
            tk.registerAgentTool(requestDecisionTool);
        }
        if (react != null && react.getAsyncTool() != null && react.getAsyncTool().isEnabled()) {
            tk.registerAgentTool(awaitToolRunTool);
            tk.registerAgentTool(asyncStatusTool);
        }
        log.info("[Orchestrator] DynamicToolkit PLANNER 已注册工具: think_summary, request_decision, await_tool_run, async_status（动作/调度工具由 ReActAgentFactory 注册）");
        return tk;
    }

    private enum ToolkitScope {
        MAIN, SUB, WORKER, PLANNER
    }

    /** 工具组描述：displayName + 一行描述（供 AgentScope 分组元信息展示） */
    private String toolGroupDescription(String toolName) {
        ToolCatalogEntry entry = toolCatalogService.find(toolName).orElse(null);
        if (entry == null) {
            return toolName;
        }
        String name = StringUtils.hasText(entry.displayName()) ? entry.displayName().strip() : toolName;
        String desc = StringUtils.hasText(entry.description()) ? entry.description().strip() : "";
        return desc.isBlank() ? name : name + "：" + oneLine(desc);
    }

    private static String oneLine(String text) {
        String collapsed = text.replaceAll("\\s+", " ");
        return collapsed.length() <= 60 ? collapsed : collapsed.substring(0, 60);
    }

    /**
     * 绑 skill 时并入 skill 声明的业务工具（去重）；`["*"]` 为全量哨兵，展开为租户启用池。
     * skillId 为空或 skill 未声明工具 → 原样返回。
     */
    private List<String> mergeSkillTools(List<String> whitelist, String skillId, String tenantId) {
        if (!StringUtils.hasText(skillId)) {
            return whitelist;
        }
        List<String> skillTools = skillCatalogService.toolIds(skillId);
        if (skillTools == null || skillTools.isEmpty()) {
            return whitelist;
        }
        if (AgentToolsJson.isStarAll(skillTools)) {
            skillTools = toolSetResolver.resolveAllEnabledTools(tenantId);
        }
        Set<String> merged = new HashSet<>();
        if (whitelist != null) {
            merged.addAll(whitelist);
        }
        merged.addAll(skillTools);
        return List.copyOf(merged);
    }

    private Toolkit buildFromWhitelist(
            List<String> whitelist,
            ToolkitScope scope,
            String skillId,
            String userId,
            String tenantId,
            String conversationKind) {
        // A-5：主 agent（MAIN）T0 恒 = (tenant, kind) 工具集配置，不与 skill 声明并集——skill 声明工具
        // 只作 schema 召回索引（v3.6）；A-1：SUB/Worker 仍并集 skill 声明工具（`["*"]` 展开为租户启用池全量），
        // 但结果须 ⊆ 租户启用池求交（skill 声明只声明不决定可用性）。
        if (scope != ToolkitScope.MAIN) {
            whitelist = mergeSkillTools(whitelist, skillId, tenantId);
            whitelist = toolSetResolver.intersectEnabledPool(whitelist, tenantId);
        }
        // skillId 保留签名兼容；方案 B 不再门控沙箱工具
        // 同轮无依赖 tool_call 并行（须 AgentScope ≥1.0.8：mergeSequential 保序，避免结果错配）
        Toolkit tk = new Toolkit(ToolkitConfig.builder().parallel(true).build());
        List<String> registered = new ArrayList<>();
        Set<String> registeredRemote = new HashSet<>();
        List<String> missing = new ArrayList<>();

        // 5.5 retrieval 分层注入（仅 MAIN）：业务工具按组注册（默认 inactive），
        // 由 ToolRetrievalMiddleware 每轮按 query 激活 Top-K；恒注入工具（HITL/沙箱/元工具）未分组始终可见。
        boolean retrieval = scope == ToolkitScope.MAIN && toolRetrievalService.retrievalEnabled();
        if (retrieval) {
            for (String toolName : whitelist) {
                if (toolName == null || toolName.isBlank() || toolRetrievalService.isAlwaysInject(toolName)) {
                    continue;
                }
                tk.createToolGroup(
                        ToolRetrievalService.groupOf(toolName),
                        toolGroupDescription(toolName),
                        false);
            }
        }

        if (scope == ToolkitScope.MAIN
                || scope == ToolkitScope.SUB
                || scope == ToolkitScope.WORKER
                || scope == ToolkitScope.PLANNER) {
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
                    if (retrieval && !toolRetrievalService.isAlwaysInject(toolName)) {
                        tk.registration()
                                .agentTool(agentTool)
                                .group(ToolRetrievalService.groupOf(toolName))
                                .apply();
                    } else {
                        tk.registerAgentTool(agentTool);
                    }
                }
                registered.add(toolName);
            }, () -> missing.add(toolName));
        }

        if (scope == ToolkitScope.MAIN
                || scope == ToolkitScope.SUB
                || scope == ToolkitScope.WORKER
                || scope == ToolkitScope.PLANNER) {
            tk.registerTool(thinkSummaryTool);
            registered.add(ThinkSummaryTool.NAME);
        }
        if (scope == ToolkitScope.MAIN
                || scope == ToolkitScope.WORKER
                || scope == ToolkitScope.PLANNER) {
            AgentExecutionProperties.React react = executionProperties.getReact();
            // spawn_subagent：MAIN（fast ReAct 主链）与 WORKER（v17.12 完整 fast ReAct，可隔离子工作）；
            // SUB（workflow 子节点 / spawn 临时子 agent）不注册防递归；PLANNER 经 dispatch_worker → Worker
            if ((scope == ToolkitScope.MAIN || scope == ToolkitScope.WORKER)
                    && react != null && react.getSubagent() != null && react.getSubagent().isEnabled()) {
                tk.registerAgentTool(spawnSubagentTool);
                registered.add(SpawnSubagentTool.NAME);
            }
            // M3 session_search：仅 task 会话 MAIN 注册（chat 不注入；workflow/SUB/PLANNER 不注册）
            if (scope == ToolkitScope.MAIN
                    && "task".equals(conversationKind)
                    && react != null && react.getSessionSearch() != null
                    && react.getSessionSearch().isEnabled()) {
                tk.registerAgentTool(sessionSearchTool);
                registered.add(SessionSearchTool.NAME);
            }
            // S-C：双阈值采纳开启时向 MAIN 提供候选技能动态加载入口；
            // 候选集经 SkillCandidateRegistry 消息级承载，无候选时工具调用会明确拒绝
            if (scope == ToolkitScope.MAIN
                    && react != null && react.getSkillAdoption() != null
                    && react.getSkillAdoption().isEnabled()) {
                tk.registerAgentTool(skillSearchTool);
                registered.add(SkillSearchTool.NAME);
            }
            // D12：request_decision 用户决策归主链——MAIN 在此注册；PLANNER 走 buildForPlanner 单独注册；
            // WORKER / SUB 不注册（决策不派发子 Agent）
            if (react != null && react.getDecision() != null && react.getDecision().isEnabled()
                    && scope == ToolkitScope.MAIN) {
                tk.registerAgentTool(requestDecisionTool);
                registered.add(RequestDecisionTool.NAME);
            }
            if (react != null && react.getAsyncTool() != null && react.getAsyncTool().isEnabled()) {
                tk.registerAgentTool(awaitToolRunTool);
                registered.add(AwaitToolRunTool.NAME);
                tk.registerAgentTool(asyncStatusTool);
                registered.add(AsyncStatusTool.NAME);
            }
        }
        if (scope == ToolkitScope.MAIN
                || scope == ToolkitScope.SUB
                || scope == ToolkitScope.WORKER
                || scope == ToolkitScope.PLANNER) {
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
