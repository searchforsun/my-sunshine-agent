package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.remote.CatalogRemoteAgentTool;
import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.orchestrator.catalog.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicToolkitFactoryTest {

    @Mock
    private RagTool ragTool;
    @Mock
    private GenericRemoteToolFactory remoteToolFactory;
    @Mock
    private ToolCatalogService toolCatalogService;
    @Mock
    private AgentExecutionProperties executionProperties;
    @Mock
    private AgentExecutionProperties.React reactProps;
    @InjectMocks
    private DynamicToolkitFactory factory;

    @Test
    void build_succeedsWhenMissingCatalogTool() {
        when(executionProperties.getReact()).thenReturn(reactProps);
        when(reactProps.getTools()).thenReturn(List.of("ghost_tool"));
        when(toolCatalogService.isRagTool("ghost_tool")).thenReturn(false);
        when(remoteToolFactory.create("ghost_tool")).thenReturn(Optional.empty());

        factory.build();
    }

    @Test
    void build_withExplicitWhitelist_registersOnlyListedTools() {
        ToolCatalogEntry financeEntry = new ToolCatalogEntry(
                "list_finance_messages", "查询待审批财务消息", "desc", "remote", "tool", "finance-list", java.util.Map.of(), "read");
        com.sunshine.orchestrator.client.ToolManagerClient toolManagerClient =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.client.ToolManagerClient.class);
        com.sunshine.orchestrator.audit.ToolAuditService toolAuditService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.audit.ToolAuditService.class);
        com.sunshine.orchestrator.hitl.HitlConfirmationService hitlService =
                org.mockito.Mockito.mock(com.sunshine.orchestrator.hitl.HitlConfirmationService.class);

        when(toolCatalogService.isRagTool("list_finance_messages")).thenReturn(false);
        when(remoteToolFactory.create("list_finance_messages"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(
                        financeEntry, toolManagerClient, toolAuditService, hitlService)));

        var toolkit = factory.build(List.of("list_finance_messages"));

        assertThat(toolkit.getToolNames()).containsExactly("list_finance_messages");
    }
}
