package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.audit.ToolAuditService;
import com.sunshine.orchestrator.agent.remote.CatalogRemoteAgentTool;
import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Agent 基础设施单测：Toolkit / ReActAgentFactory 装配 */
class AgentInfraTest {

    @Test
    void reactAgentFactory_resolveToolkitUsesDynamicFactory() throws Exception {
        Method resolveToolkit = ReActAgentFactory.class.getDeclaredMethod(
                "resolveToolkit", com.sunshine.orchestrator.agent.runtime.AgentRunRequest.class);
        assertThat(Modifier.isPrivate(resolveToolkit.getModifiers())).isFalse();
    }

    @Test
    void dynamicToolkit_registersWhitelistedTools() {
        RagTool ragTool = Mockito.mock(RagTool.class);
        GenericRemoteToolFactory remoteToolFactory = Mockito.mock(GenericRemoteToolFactory.class);
        ToolCatalogService toolCatalogService = Mockito.mock(ToolCatalogService.class);
        ToolSetResolver toolSetResolver = Mockito.mock(ToolSetResolver.class);
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();

        when(toolSetResolver.resolveReactTools("default")).thenReturn(List.of(
                "search_knowledge", "sdk__sunshine-finance__list_finance_messages", "sdk__sunshine-oa__list_oa_tasks"));

        ToolCatalogEntry financeEntry = new ToolCatalogEntry(
                "sdk__sunshine-finance__list_finance_messages", "查询待审批财务消息", "desc", "remote", "sdk", "sunshine-finance", "", null, Map.of(), "read", false, true, true, null);
        ToolCatalogEntry oaEntry = new ToolCatalogEntry(
                "sdk__sunshine-oa__list_oa_tasks", "查询 OA 待办", "desc", "remote", "sdk", "sunshine-oa", "", null, Map.of(), "read", false, true, true, null);
        ToolManagerClient toolManagerClient = Mockito.mock(ToolManagerClient.class);
        ToolAuditService toolAuditService = Mockito.mock(ToolAuditService.class);
        com.sunshine.orchestrator.hitl.HitlConfirmationService hitlService =
                Mockito.mock(com.sunshine.orchestrator.hitl.HitlConfirmationService.class);

        when(toolCatalogService.isRagTool("search_knowledge")).thenReturn(true);
        when(toolCatalogService.isRagTool("sdk__sunshine-finance__list_finance_messages")).thenReturn(false);
        when(toolCatalogService.isRagTool("sdk__sunshine-oa__list_oa_tasks")).thenReturn(false);
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(remoteToolFactory.create("sdk__sunshine-finance__list_finance_messages"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(
                        financeEntry, toolManagerClient, toolAuditService, hitlService)));
        when(remoteToolFactory.create("sdk__sunshine-oa__list_oa_tasks"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(
                        oaEntry, toolManagerClient, toolAuditService, hitlService)));

        DynamicToolkitFactory factory = new DynamicToolkitFactory(
                ragTool,
                Mockito.mock(ManageTasksTool.class),
                remoteToolFactory,
                toolCatalogService,
                toolSetResolver,
                executionProperties,
                Mockito.mock(com.sunshine.orchestrator.sandbox.SandboxAgentTools.class));
        Toolkit toolkit = factory.build();

        assertThat(toolkit).isNotNull();
        assertThat(toolkit.getToolNames()).contains(
                RagTool.NAME,
                "sdk__sunshine-finance__list_finance_messages",
                "sdk__sunshine-oa__list_oa_tasks");
    }

    @Test
    void reactAgentFactory_isSpringComponent() {
        assertThat(ReActAgentFactory.class.isAnnotationPresent(
                org.springframework.stereotype.Component.class)).isTrue();
    }
}
