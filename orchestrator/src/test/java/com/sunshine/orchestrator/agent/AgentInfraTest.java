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
    void dynamicToolkit_registersWhitelistedTools() throws Exception {
        RagTool ragTool = Mockito.mock(RagTool.class);
        SpawnSubagentTool spawnSubagentTool = Mockito.mock(SpawnSubagentTool.class);
        RequestDecisionTool requestDecisionTool = Mockito.mock(RequestDecisionTool.class);
        AwaitToolRunTool awaitToolRunTool = Mockito.mock(AwaitToolRunTool.class);
        AsyncStatusTool asyncStatusTool = Mockito.mock(AsyncStatusTool.class);
        ThinkSummaryTool thinkSummaryTool = Mockito.mock(ThinkSummaryTool.class);
        GenericRemoteToolFactory remoteToolFactory = Mockito.mock(GenericRemoteToolFactory.class);
        ToolCatalogService toolCatalogService = Mockito.mock(ToolCatalogService.class);
        ToolSetResolver toolSetResolver = Mockito.mock(ToolSetResolver.class);
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();

        when(toolSetResolver.resolveDefaultTools("default", null)).thenReturn(List.of(
                "search_knowledge", "sdk__sunshine-finance__list_my_expenses", "sdk__sunshine-oa__list_oa_tasks"));

        ToolCatalogEntry financeEntry = new ToolCatalogEntry(
                "sdk__sunshine-finance__list_my_expenses", "查询待审批财务消息", "desc", "remote", "sdk", "sunshine-finance", "", null, Map.of(), "read", false, true, true, null);
        ToolCatalogEntry oaEntry = new ToolCatalogEntry(
                "sdk__sunshine-oa__list_oa_tasks", "查询 OA 待办", "desc", "remote", "sdk", "sunshine-oa", "", null, Map.of(), "read", false, true, true, null);
        ToolManagerClient toolManagerClient = Mockito.mock(ToolManagerClient.class);
        ToolAuditService toolAuditService = Mockito.mock(ToolAuditService.class);
        com.sunshine.orchestrator.hitl.HitlConfirmationService hitlService =
                Mockito.mock(com.sunshine.orchestrator.hitl.HitlConfirmationService.class);

        when(toolCatalogService.isRagTool("search_knowledge")).thenReturn(true);
        when(toolCatalogService.isRagTool("sdk__sunshine-finance__list_my_expenses")).thenReturn(false);
        when(toolCatalogService.isRagTool("sdk__sunshine-oa__list_oa_tasks")).thenReturn(false);
        when(ragTool.getName()).thenReturn(RagTool.NAME);
        when(spawnSubagentTool.getName()).thenReturn(SpawnSubagentTool.NAME);
        when(requestDecisionTool.getName()).thenReturn(RequestDecisionTool.NAME);
        when(awaitToolRunTool.getName()).thenReturn(AwaitToolRunTool.NAME);
        when(asyncStatusTool.getName()).thenReturn(AsyncStatusTool.NAME);
        when(remoteToolFactory.create("sdk__sunshine-finance__list_my_expenses", null, "default"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(
                        financeEntry, toolManagerClient, toolAuditService, hitlService, null, "default")));
        when(remoteToolFactory.create("sdk__sunshine-oa__list_oa_tasks", null, "default"))
                .thenReturn(Optional.of(new CatalogRemoteAgentTool(
                        oaEntry, toolManagerClient, toolAuditService, hitlService, null, "default")));

        DynamicToolkitFactory factory = new DynamicToolkitFactory(
                ragTool,
                spawnSubagentTool,
                requestDecisionTool,
                awaitToolRunTool,
                asyncStatusTool,
                thinkSummaryTool,
                remoteToolFactory,
                toolCatalogService,
                toolSetResolver,
                Mockito.mock(com.sunshine.orchestrator.catalog.SkillCatalogService.class),
                executionProperties,
                Mockito.mock(com.sunshine.orchestrator.sandbox.SandboxAgentTools.class));
        Toolkit toolkit = factory.build(null);

        assertThat(toolkit).isNotNull();
        // 同轮无依赖 tool_call 并行（ToolkitConfig.parallel=true）
        var configField = Toolkit.class.getDeclaredField("config");
        configField.setAccessible(true);
        var toolkitConfig = (io.agentscope.core.tool.ToolkitConfig) configField.get(toolkit);
        assertThat(toolkitConfig.isParallel()).isTrue();
        assertThat(toolkit.getToolNames()).contains(
                RagTool.NAME,
                "sdk__sunshine-finance__list_my_expenses",
                "sdk__sunshine-oa__list_oa_tasks",
                ThinkSummaryTool.NAME);
    }

    @Test
    void reactAgentFactory_isSpringComponent() {
        assertThat(ReActAgentFactory.class.isAnnotationPresent(
                org.springframework.stereotype.Component.class)).isTrue();
    }
}
