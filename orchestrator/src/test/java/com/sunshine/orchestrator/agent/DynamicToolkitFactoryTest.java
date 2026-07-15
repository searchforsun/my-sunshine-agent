package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.remote.CatalogRemoteAgentTool;
import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicToolkitFactoryTest {

    @Mock
    private RagTool ragTool;
    @Mock
    private ManageTasksTool manageTasksTool;
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
    private SkillCatalogService skillCatalogService;
    @Mock
    private SandboxAgentTools sandboxAgentTools;
    @InjectMocks
    private DynamicToolkitFactory factory;

    @Test
    void build_withTaskboardEnabled_registersManageTasksAndSearchKnowledge() {
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getTaskboard()).thenReturn(new AgentExecutionProperties.React.Taskboard() {{
            setEnabled(true);
        }});

        var toolkit = factory.build();

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME, ManageTasksTool.NAME);
        assertThat(toolkit.getToolNames()).noneMatch(n -> n.startsWith("sandbox__"));
    }

    @Test
    void build_alwaysRegistersSearchKnowledge_evenWithEmptyWhitelist() {
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getTaskboard()).thenReturn(new AgentExecutionProperties.React.Taskboard());

        var toolkit = factory.build();

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
    }

    @Test
    void build_succeedsWhenMissingCatalogTool() {
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of("ghost_tool"));
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(toolCatalogService.isRagTool("ghost_tool")).thenReturn(false);
        when(remoteToolFactory.create("ghost_tool")).thenReturn(Optional.empty());
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getTaskboard()).thenReturn(new AgentExecutionProperties.React.Taskboard());

        factory.build();
    }

    @Test
    void buildForSubAgent_withoutExtraTools_registersSearchKnowledgeOnly() {
        when(ragTool.getName()).thenReturn(RagTool.NAME);

        var toolkit = factory.buildForSubAgent(null, "default");

        assertThat(toolkit.getToolNames()).containsExactly(RagTool.NAME);
    }

    @Test
    void buildForSubAgent_withExplicitWhitelist_includesRagAndListedTools() {
        ToolCatalogEntry financeEntry = new ToolCatalogEntry(
                "sdk__sunshine-finance__list_finance_messages", "查询待审批财务消息", "desc", "remote", "sdk", "sunshine-finance", "", null, java.util.Map.of(), "read", false, true, true, null);
        com.sunshine.orchestrator.client.ToolManagerClient toolManagerClient =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.client.ToolManagerClient.class);
        com.sunshine.orchestrator.audit.ToolAuditService toolAuditService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.audit.ToolAuditService.class);
        com.sunshine.orchestrator.hitl.HitlConfirmationService hitlService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.hitl.HitlConfirmationService.class);

        when(toolSetResolver.intersectEnabledPool(List.of("sdk__sunshine-finance__list_finance_messages"), "default"))
                .thenReturn(List.of("sdk__sunshine-finance__list_finance_messages"));
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(toolCatalogService.isRagTool("sdk__sunshine-finance__list_finance_messages")).thenReturn(false);
        when(remoteToolFactory.create("sdk__sunshine-finance__list_finance_messages"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(
                        financeEntry, toolManagerClient, toolAuditService, hitlService)));

        var toolkit = factory.buildForSubAgent(List.of("sdk__sunshine-finance__list_finance_messages"), "default");

        assertThat(toolkit.getToolNames()).containsExactly(
                RagTool.NAME, "sdk__sunshine-finance__list_finance_messages");
    }

    @Test
    void build_withExplicitWhitelist_registersOnlyListedTools() {
        ToolCatalogEntry mcpEntry = new ToolCatalogEntry(
                "mcp_search", "MCP 搜索", "desc", "mcp", "mcp", "demo-server", "", null, java.util.Map.of(), "read", false, true, true, null);
        com.sunshine.orchestrator.client.ToolManagerClient toolManagerClient =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.client.ToolManagerClient.class);
        com.sunshine.orchestrator.audit.ToolAuditService toolAuditService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.audit.ToolAuditService.class);
        com.sunshine.orchestrator.hitl.HitlConfirmationService hitlService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.hitl.HitlConfirmationService.class);

        when(toolSetResolver.intersectEnabledPool(List.of("mcp_search"), "default"))
                .thenReturn(List.of("mcp_search"));
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(toolCatalogService.isRagTool("mcp_search")).thenReturn(false);
        when(remoteToolFactory.create("mcp_search"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(
                        mcpEntry, toolManagerClient, toolAuditService, hitlService)));

        var toolkit = factory.build(List.of("mcp_search"));

        assertThat(toolkit.getToolNames()).containsExactly(RagTool.NAME, "mcp_search");
    }

    @Test
    void build_withExplicitWhitelist_filtersOutDisabledTools() {
        when(toolSetResolver.intersectEnabledPool(List.of("ghost_tool", "sdk__sunshine-finance__list_finance_messages"), "default"))
                .thenReturn(List.of("sdk__sunshine-finance__list_finance_messages"));
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        ToolCatalogEntry financeEntry = new ToolCatalogEntry(
                "sdk__sunshine-finance__list_finance_messages", "查询待审批财务消息", "desc", "remote", "sdk", "sunshine-finance", "", null, java.util.Map.of(), "read", false, true, true, null);
        com.sunshine.orchestrator.client.ToolManagerClient toolManagerClient =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.client.ToolManagerClient.class);
        com.sunshine.orchestrator.audit.ToolAuditService toolAuditService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.audit.ToolAuditService.class);
        com.sunshine.orchestrator.hitl.HitlConfirmationService hitlService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.hitl.HitlConfirmationService.class);

        when(toolCatalogService.isRagTool("sdk__sunshine-finance__list_finance_messages")).thenReturn(false);
        when(remoteToolFactory.create("sdk__sunshine-finance__list_finance_messages"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(
                        financeEntry, toolManagerClient, toolAuditService, hitlService)));

        var toolkit = factory.build(List.of("ghost_tool", "sdk__sunshine-finance__list_finance_messages"));

        assertThat(toolkit.getToolNames()).containsExactly(
                RagTool.NAME, "sdk__sunshine-finance__list_finance_messages");
    }

    @Test
    void build_sandboxNone_doesNotRegisterSandboxTools() {
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getTaskboard()).thenReturn(new AgentExecutionProperties.React.Taskboard());
        when(skillCatalogService.find("plain-skill")).thenReturn(Optional.of(
                new SkillCatalogEntry("plain-skill", "Plain", "d", "overlay", 1, true, "none", null)));

        var toolkit = factory.build("default", "plain-skill");

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
        assertThat(toolkit.getToolNames()).noneMatch(n -> n.startsWith("sandbox__"));
    }

    @Test
    void build_sandboxDocker_registersSixSandboxTools() {
        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of());
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getTaskboard()).thenReturn(new AgentExecutionProperties.React.Taskboard());
        when(skillCatalogService.find("coding-skill")).thenReturn(Optional.of(
                new SkillCatalogEntry("coding-skill", "Coding", "d", "overlay", 1, true, "docker", null)));
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(sandboxAgentTools.all()).thenReturn(sandboxTools);

        var toolkit = factory.build("default", "coding-skill");

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
        assertThat(toolkit.getToolNames()).containsAll(SandboxIds.ALL);
    }

    @Test
    void buildForSubAgent_sandboxDocker_registersSixSandboxTools() {
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(skillCatalogService.find("coding-skill")).thenReturn(Optional.of(
                new SkillCatalogEntry("coding-skill", "Coding", "d", "overlay", 1, true, "docker", null)));
        List<AgentTool> sandboxTools = stubSandboxTools();
        when(sandboxAgentTools.all()).thenReturn(sandboxTools);

        var toolkit = factory.buildForSubAgent(null, "default", "coding-skill");

        assertThat(toolkit.getToolNames()).contains(RagTool.NAME);
        assertThat(toolkit.getToolNames()).containsAll(SandboxIds.ALL);
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
