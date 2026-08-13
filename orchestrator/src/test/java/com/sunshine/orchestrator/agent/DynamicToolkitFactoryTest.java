package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.remote.CatalogRemoteAgentTool;
import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.sandbox.SandboxAgentTools;
import com.sunshine.orchestrator.sandbox.SandboxIds;
import io.agentscope.core.tool.AgentTool;
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
    private ThinkSummaryTool thinkSummaryTool;
    @Mock
    private GenericRemoteToolFactory remoteToolFactory;
    @Mock
    private ToolCatalogService toolCatalogService;
    @Mock
    private ToolSetResolver toolSetResolver;
    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private AgentExecutionProperties.React reactProps;
    @Mock
    private SandboxAgentTools sandboxAgentTools;
    @InjectMocks
    private DynamicToolkitFactory factory;

    @Test
    void build_withTaskboardEnabled_doesNotRegisterManageTasks_nativeTodoWriteOwnedByEnableTaskList() {
        // P3 原生 TaskList：todo_write 由 ReActAgent.enableTaskList 在 build 时注册，
        // 工厂不再注册自研 manage_tasks（避免两个任务板工具并存）。
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
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
    void build_withSubagentEnabled_registersSpawnSubagent() {
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
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
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
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
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
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
    void buildForPlanner_doesNotRegisterRequestDecision() {
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getSubagent()).thenReturn(new AgentExecutionProperties.React.Subagent());
        when(reactProps.getDecision()).thenReturn(new AgentExecutionProperties.React.Decision() {{
            setEnabled(true);
        }});
        when(sandboxAgentTools.all()).thenReturn(List.of());

        var toolkit = factory.buildForPlanner("default", null, "u1");

        assertThat(toolkit.getToolNames()).doesNotContain(RequestDecisionTool.NAME);
        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
        assertThat(toolkit.getToolNames()).contains(SpawnSubagentTool.NAME);
    }

    @Test
    void build_alwaysRegistersSearchKnowledgeAndSandbox_evenWithEmptyWhitelist() {
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
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
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of("ghost_tool"));
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
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
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
