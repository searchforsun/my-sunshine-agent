package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.remote.CatalogRemoteAgentTool;
import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import com.sunshine.orchestrator.catalog.ToolCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentPromptProperties;
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

class SunshineAgentMemoryTest {

    @Test
    void toolkit_beanMethodUsesDynamicToolkitFactory() throws Exception {
        Method toolkitMethod = AgentConfig.class.getDeclaredMethod(
                "toolkit", DynamicToolkitFactory.class);
        assertThat(toolkitMethod.isAnnotationPresent(org.springframework.context.annotation.Bean.class)).isTrue();
        assertThat(Modifier.isPublic(toolkitMethod.getModifiers())).isTrue();
    }

    @Test
    void dynamicToolkit_registersWhitelistedTools() {
        RagTool ragTool = Mockito.mock(RagTool.class);
        GenericRemoteToolFactory remoteToolFactory = Mockito.mock(GenericRemoteToolFactory.class);
        ToolCatalogService toolCatalogService = Mockito.mock(ToolCatalogService.class);
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();
        executionProperties.getReact().setTools(
                List.of("search_knowledge", "list_finance_messages", "list_oa_tasks"));

        ToolCatalogEntry financeEntry = new ToolCatalogEntry(
                "list_finance_messages", "查询待审批财务消息", "desc", "remote", "tool", "finance-list", Map.of());
        ToolCatalogEntry oaEntry = new ToolCatalogEntry(
                "list_oa_tasks", "查询 OA 待办", "desc", "remote", "tool", "oa-tasks", Map.of());
        ToolManagerClient toolManagerClient = Mockito.mock(ToolManagerClient.class);

        when(toolCatalogService.isRagTool("search_knowledge")).thenReturn(true);
        when(toolCatalogService.isRagTool("list_finance_messages")).thenReturn(false);
        when(toolCatalogService.isRagTool("list_oa_tasks")).thenReturn(false);
        when(remoteToolFactory.create("list_finance_messages"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(financeEntry, toolManagerClient)));
        when(remoteToolFactory.create("list_oa_tasks"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(oaEntry, toolManagerClient)));

        DynamicToolkitFactory factory = new DynamicToolkitFactory(
                ragTool,
                remoteToolFactory,
                toolCatalogService,
                executionProperties);
        Toolkit toolkit = factory.build();

        assertThat(toolkit).isNotNull();
        assertThat(toolkit.getToolNames()).contains("list_finance_messages", "list_oa_tasks");
    }

    @Test
    void reactAgentFactory_isRequestScopedComponent() {
        assertThat(ReActAgentFactory.class.isAnnotationPresent(
                org.springframework.stereotype.Component.class)).isTrue();
    }
}
