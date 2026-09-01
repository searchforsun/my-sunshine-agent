package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.remote.CatalogRemoteAgentTool;
import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.sandbox.SandboxAgentTools;
import com.sunshine.orchestrator.sandbox.SandboxIds;
import io.agentscope.core.tool.AgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicToolkitFactoryTest {

    @Mock
    private RagTool ragTool;
    @Mock
    private SpawnSubagentTool spawnSubagentTool;
    @Mock
    private RequestDecisionTool requestDecisionTool;
    @Mock
    private AwaitToolRunTool awaitToolRunTool;
    @Mock
    private AsyncStatusTool asyncStatusTool;
    @Mock
    private ThinkSummaryTool thinkSummaryTool;
    @Mock
    private GenericRemoteToolFactory remoteToolFactory;
    @Mock
    private ToolCatalogService toolCatalogService;
    @Mock
    private SkillCatalogService skillCatalogService;
    @Mock
    private ToolSetResolver toolSetResolver;
    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private AgentExecutionProperties.React reactProps;
    @Mock
    private SandboxAgentTools sandboxAgentTools;
    @Mock
    private SessionSearchTool sessionSearchTool;
    @Mock
    private SkillSearchTool skillSearchTool;
    @Mock
    private ToolRetrievalService toolRetrievalService;
    @InjectMocks
    private DynamicToolkitFactory factory;

    /** 技能加载元工具常驻 MAIN：stub 名称避免注册时报 Tool name cannot be null（lenient：部分测试不进入 MAIN 分支） */
    @BeforeEach
    void stubSkillSearchName() {
        org.mockito.Mockito.lenient().when(skillSearchTool.getName()).thenReturn(SkillSearchTool.NAME);
    }

    @Test
    void build_withTaskboardEnabled_doesNotRegisterManageTasks_nativeTodoWriteOwnedByEnableTaskList() {
        // P3 原生 TaskList：todo_write 由 ReActAgent.enableTaskList 在 build 时注册，
        // 工厂不再注册自研 manage_tasks（避免两个任务板工具并存）。
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(toolSetResolver.resolveDefaultTools("default", null)).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(sandboxAgentTools.all()).thenReturn(sandboxTools);

        var toolkit = factory.build(null);

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
        assertThat(toolkit.getToolNames()).doesNotContain("todo_write");
        assertThat(toolkit.getToolNames()).containsAll(SandboxIds.ALL);
    }

    @Test
    void build_withTaskKind_resolvesTaskToolsNotChat() {
        when(toolSetResolver.resolveDefaultTools("default", "task")).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(sessionSearchTool.getName()).thenReturn(SessionSearchTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(reactProps.getSessionSearch()).thenReturn(new AgentExecutionProperties.React.SessionSearch());
        when(sandboxAgentTools.all()).thenReturn(List.of());

        factory.build("default", null, "u1", "task");

        verify(toolSetResolver).resolveDefaultTools("default", "task");
        verify(toolSetResolver, never()).resolveChatTools("default");
    }

    @Test
    void build_taskKindEnabled_registersSessionSearchTool() {
        // M3：task 会话 MAIN 注册 sunshine_session_search（本会话正文按需恢复）。
        when(toolSetResolver.resolveDefaultTools("default", "task")).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(sessionSearchTool.getName()).thenReturn(SessionSearchTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(reactProps.getSessionSearch()).thenReturn(new AgentExecutionProperties.React.SessionSearch() {{
            setEnabled(true);
        }});
        when(sandboxAgentTools.all()).thenReturn(List.of());

        var toolkit = factory.build("default", null, "u1", "task");

        assertThat(toolkit.getToolNames()).contains(SessionSearchTool.NAME);
    }

    @Test
    void build_chatKind_doesNotRegisterSessionSearchTool() {
        // M3：session_search 仅 task 会话；chat 会话不注册。
        when(toolSetResolver.resolveDefaultTools("default", "chat")).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(sandboxAgentTools.all()).thenReturn(List.of());

        var toolkit = factory.build("default", null, "u1", "chat");

        assertThat(toolkit.getToolNames()).doesNotContain(SessionSearchTool.NAME);
    }

    @Test
    void build_taskKindDisabled_doesNotRegisterSessionSearchTool() {
        when(toolSetResolver.resolveDefaultTools("default", "task")).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(reactProps.getSessionSearch()).thenReturn(new AgentExecutionProperties.React.SessionSearch() {{
            setEnabled(false);
        }});
        when(sandboxAgentTools.all()).thenReturn(List.of());

        var toolkit = factory.build("default", null, "u1", "task");

        assertThat(toolkit.getToolNames()).doesNotContain(SessionSearchTool.NAME);
    }

    @Test
    void build_withSubagentEnabled_registersSpawnSubagent() {
        when(toolSetResolver.resolveDefaultTools("default", null)).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent() {{
            setEnabled(true);
        }});
        when(sandboxAgentTools.all()).thenReturn(List.of());

        var toolkit = factory.build(null);

        assertThat(toolkit.getToolNames()).contains(SpawnSubagentTool.NAME);
    }

    @Test
    void build_withSubagentDisabled_doesNotRegisterSpawnSubagent() {
        when(toolSetResolver.resolveDefaultTools("default", null)).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent() {{
            setEnabled(false);
        }});
        when(sandboxAgentTools.all()).thenReturn(List.of());

        var toolkit = factory.build(null);

        assertThat(toolkit.getToolNames()).doesNotContain(SpawnSubagentTool.NAME);
    }

    @Test
    void build_withDecisionEnabled_registersRequestDecision() {
        when(toolSetResolver.resolveDefaultTools("default", null)).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(requestDecisionTool.getName()).thenReturn(RequestDecisionTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(reactProps.getDecision()).thenReturn(new AgentExecutionProperties.React.Decision() {{
            setEnabled(true);
        }});
        when(sandboxAgentTools.all()).thenReturn(List.of());

        var toolkit = factory.build(null);

        assertThat(toolkit.getToolNames()).contains(RequestDecisionTool.NAME);
    }

    @Test
    void buildForSubAgent_doesNotRegisterRequestDecision() {
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(sandboxAgentTools.all()).thenReturn(sandboxTools);

        var toolkit = factory.buildForSubAgent(null, "default", "coding-skill", "u1");

        assertThat(toolkit.getToolNames()).doesNotContain(RequestDecisionTool.NAME);
    }

    @Test
    void buildForWorker_registersAsyncMetaTools_andSubagent() {
        // v17.12：Worker = SUB 基础（RAG/沙箱/think_summary）+ await_tool_run + async_status + spawn_subagent；
        // 不注册 request_decision（用户决策归主链）。
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(awaitToolRunTool.getName()).thenReturn(AwaitToolRunTool.NAME);
        when(asyncStatusTool.getName()).thenReturn(AsyncStatusTool.NAME);
        when(sandboxAgentTools.all()).thenReturn(sandboxTools);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent() {{
            setEnabled(true);
        }});
        when(reactProps.getAsyncTool()).thenReturn(new AgentExecutionProperties.React.AsyncTool());

        var toolkit = factory.buildForWorker(List.of("ghost_tool"), "default", null, "u1");

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
        assertThat(toolkit.getToolNames()).containsAll(SandboxIds.ALL);
        assertThat(toolkit.getToolNames()).contains(AwaitToolRunTool.NAME);
        assertThat(toolkit.getToolNames()).contains(AsyncStatusTool.NAME);
        assertThat(toolkit.getToolNames()).contains(SpawnSubagentTool.NAME);
        assertThat(toolkit.getToolNames()).doesNotContain(RequestDecisionTool.NAME);
    }

    @Test
    void buildForPlanner_withDecisionEnabled_registersRequestDecision() {
        // D12：Planner MAIN 与 Chat MAIN 同契约——decision.enabled 下注册 request_decision；
        // spawn_subagent 仍不注入（用户决策归主链，不派发子 Agent）。
        org.mockito.Mockito.lenient().when(toolSetResolver.resolveDefaultTools("default", null)).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(requestDecisionTool.getName()).thenReturn(RequestDecisionTool.NAME);
        org.mockito.Mockito.lenient().when(executionProperties.getReact()).thenReturn(reactProps);
        org.mockito.Mockito.lenient().when(reactProps.getDecision()).thenReturn(new AgentExecutionProperties.React.Decision() {{
            setEnabled(true);
        }});
        org.mockito.Mockito.lenient().when(reactProps.getAsyncTool())
                .thenReturn(new AgentExecutionProperties.React.AsyncTool());
        org.mockito.Mockito.lenient().when(awaitToolRunTool.getName()).thenReturn(AwaitToolRunTool.NAME);
        org.mockito.Mockito.lenient().when(asyncStatusTool.getName()).thenReturn(AsyncStatusTool.NAME);

        var toolkit = factory.buildForPlanner("default", null, "u1");

        assertThat(toolkit.getToolNames()).contains(RequestDecisionTool.NAME);
        assertThat(toolkit.getToolNames()).doesNotContain(SpawnSubagentTool.NAME);
        // Planner 只持有动作元工具（plan_submit / self_assess / dispatch_worker / think_summary / request_decision / await_tool_run / async_status）；
        // await_tool_run 用于收集 Worker handoff（dispatch_worker 强制异步），缺失会导致 Planner 空转幻构工具名。
        assertThat(toolkit.getToolNames()).contains(AwaitToolRunTool.NAME);
        assertThat(toolkit.getToolNames()).contains(AsyncStatusTool.NAME);
        // 不暴露业务工具（沙箱 / search_knowledge / 财务 / OA 等）以保持「纯规划」边界。
        assertThat(toolkit.getToolNames()).doesNotContain(RagTool.NAME);
        assertThat(toolkit.getToolNames()).doesNotContain("sandbox__exec");
    }

    @Test
    void buildForPlanner_withDecisionDisabled_doesNotRegisterRequestDecision() {
        // D12：decision.enabled=false 时 Planner 不注册 request_decision（与 Chat MAIN 同契约）。
        org.mockito.Mockito.lenient().when(executionProperties.getReact()).thenReturn(reactProps);
        org.mockito.Mockito.lenient().when(reactProps.getDecision()).thenReturn(new AgentExecutionProperties.React.Decision() {{
            setEnabled(false);
        }});
        org.mockito.Mockito.lenient().when(reactProps.getAsyncTool())
                .thenReturn(new AgentExecutionProperties.React.AsyncTool());
        org.mockito.Mockito.lenient().when(awaitToolRunTool.getName()).thenReturn(AwaitToolRunTool.NAME);
        org.mockito.Mockito.lenient().when(asyncStatusTool.getName()).thenReturn(AsyncStatusTool.NAME);

        var toolkit = factory.buildForPlanner("default", null, "u1");

        assertThat(toolkit.getToolNames()).doesNotContain(RequestDecisionTool.NAME);
        assertThat(toolkit.getToolNames()).contains(AwaitToolRunTool.NAME);
        assertThat(toolkit.getToolNames()).contains(AsyncStatusTool.NAME);
    }

    @Test
    void build_alwaysRegistersSearchKnowledgeAndSandbox_evenWithEmptyWhitelist() {
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(toolSetResolver.resolveDefaultTools("default", null)).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(sandboxAgentTools.all()).thenReturn(sandboxTools);

        var toolkit = factory.build(null);

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
        assertThat(toolkit.getToolNames()).containsAll(SandboxIds.ALL);
    }

    @Test
    void build_succeedsWhenMissingCatalogTool() {
        when(toolSetResolver.resolveDefaultTools("default", null)).thenReturn(List.of("ghost_tool"));
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(sandboxAgentTools.all()).thenReturn(List.of());
        when(toolCatalogService.isRagTool("ghost_tool")).thenReturn(false);
        when(remoteToolFactory.create(eq("ghost_tool"), isNull(), eq("default"))).thenReturn(Optional.empty());

        var toolkit = factory.build(null);

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
    }

    @Test
    void build_withWhitelist_registersRemoteAndSandbox() {
        when(toolSetResolver.intersectEnabledPool(List.of("ghost_tool", "sdk__sunshine-finance__list_my_expenses"), "default"))
                .thenReturn(List.of("sdk__sunshine-finance__list_my_expenses"));
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(sandboxAgentTools.all()).thenReturn(List.of());
        ToolCatalogEntry financeEntry = new ToolCatalogEntry(
                "sdk__sunshine-finance__list_my_expenses", "查询待审批财务消息", "desc", "remote", "sdk", "sunshine-finance", "", null, java.util.Map.of(), "read", false, true, true, null);
        com.sunshine.orchestrator.client.ToolManagerClient toolManagerClient =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.client.ToolManagerClient.class);
        com.sunshine.orchestrator.audit.ToolAuditService toolAuditService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.audit.ToolAuditService.class);
        com.sunshine.orchestrator.hitl.HitlConfirmationService hitlService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.hitl.HitlConfirmationService.class);

        when(toolCatalogService.isRagTool("sdk__sunshine-finance__list_my_expenses")).thenReturn(false);
        when(remoteToolFactory.create(eq("sdk__sunshine-finance__list_my_expenses"), eq("u1"), eq("default")))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(
                        financeEntry, toolManagerClient, toolAuditService, hitlService, "u1", "default")));

        var toolkit = factory.build(
                List.of("ghost_tool", "sdk__sunshine-finance__list_my_expenses"),
                "default", null, "u1");

        assertThat(toolkit.getToolNames()).contains(
                RagTool.NAME, "sdk__sunshine-finance__list_my_expenses");
    }

    @Test
    void build_withoutSkill_stillRegistersSixSandboxTools() {
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(toolSetResolver.resolveDefaultTools("default", null)).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(sandboxAgentTools.all()).thenReturn(sandboxTools);

        var toolkit = factory.build("default", null, null);

        assertThat(toolkit.getToolNames()).containsAll(SandboxIds.ALL);
    }

    @Test
    void buildForSubAgent_registersSandbox_withoutManageTasksOrSpawnSubagent() {
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(sandboxAgentTools.all()).thenReturn(sandboxTools);

        var toolkit = factory.buildForSubAgent(null, "default", "coding-skill", "u1");

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
        assertThat(toolkit.getToolNames()).containsAll(SandboxIds.ALL);
        assertThat(toolkit.getToolNames()).doesNotContain("todo_write");
        assertThat(toolkit.getToolNames()).doesNotContain(SpawnSubagentTool.NAME);
    }

    @Test
    void build_main_doesNotMergeSkillDeclaredTools() {
        // A-5：主 agent T0 恒 = (tenant, kind) 工具集配置，不与 skill 声明并集（skill 声明只作 schema 召回索引）
        when(toolSetResolver.resolveDefaultTools("default", "chat")).thenReturn(List.of("sdk__sunshine-biz__tool_a"));
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(sandboxAgentTools.all()).thenReturn(List.of());
        when(toolCatalogService.isRagTool("sdk__sunshine-biz__tool_a")).thenReturn(false);
        when(remoteToolFactory.create(eq("sdk__sunshine-biz__tool_a"), eq("u1"), eq("default")))
                .thenReturn(Optional.empty());

        factory.build("default", "coding-skill", "u1", "chat");

        // skill 声明工具不得进入 MAIN T0 装配
        verify(skillCatalogService, never()).toolIds("coding-skill");
    }

    @Test
    void buildForSubAgent_mergesSkillDeclaredTools_thenIntersectsEnabledPool() {
        // A-1：SUB/Worker 仍并集 skill 声明工具，但结果须 ⊆ 租户启用池（越界剔除）
        when(toolSetResolver.intersectEnabledPool(List.of("sdk__sunshine-biz__tool_a"), "default"))
                .thenReturn(List.of("sdk__sunshine-biz__tool_a"));
        when(skillCatalogService.toolIds("coding-skill")).thenReturn(List.of("sdk__sunshine-biz__tool_b"));
        when(toolSetResolver.intersectEnabledPool(List.of("sdk__sunshine-biz__tool_a", "sdk__sunshine-biz__tool_b"), "default"))
                .thenReturn(List.of("sdk__sunshine-biz__tool_a"));
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(sandboxAgentTools.all()).thenReturn(List.of());
        when(toolCatalogService.isRagTool("sdk__sunshine-biz__tool_a")).thenReturn(false);
        when(remoteToolFactory.create(eq("sdk__sunshine-biz__tool_a"), eq("u1"), eq("default")))
                .thenReturn(Optional.empty());

        var toolkit = factory.buildForSubAgent(List.of("sdk__sunshine-biz__tool_a"), "default", "coding-skill", "u1");

        assertThat(toolkit.getToolNames()).doesNotContain("sdk__sunshine-biz__tool_b");
    }

    private static List<AgentTool> stubSandboxTools() {
        List<AgentTool> tools = new java.util.ArrayList<>();
        for (String id : SandboxIds.ALL) {
            AgentTool t = mock(AgentTool.class);
            when(t.getName()).thenReturn(id);
            tools.add(t);
        }
        return tools;
    }
}
