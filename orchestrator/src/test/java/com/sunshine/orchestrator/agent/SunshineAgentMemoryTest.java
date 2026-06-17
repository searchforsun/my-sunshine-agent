package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.client.ToolManagerClient;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * ReActAgent 不再依赖全局 InMemoryMemory；Toolkit 由 DynamicToolkitFactory 按白名单注册。
 */
class SunshineAgentMemoryTest {

    @Test
    void toolkit_beanMethodUsesDynamicToolkitFactory() throws Exception {
        Method toolkitMethod = AgentConfig.class.getDeclaredMethod(
                "toolkit", DynamicToolkitFactory.class);
        assertThat(toolkitMethod.isAnnotationPresent(Bean.class)).isTrue();
        assertThat(Modifier.isPublic(toolkitMethod.getModifiers())).isTrue();
    }

    @Test
    void dynamicToolkit_registersWhitelistedTools() {
        RagTool ragTool = Mockito.mock(RagTool.class);
        ToolManagerClient toolManagerClient = Mockito.mock(ToolManagerClient.class);
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();
        executionProperties.getReact().setTools(
                java.util.List.of("search_knowledge", "list_finance_messages"));

        DynamicToolkitFactory factory = new DynamicToolkitFactory(
                ragTool, toolManagerClient, executionProperties);
        Toolkit toolkit = factory.build();

        assertThat(toolkit).isNotNull();
        verify(ragTool, Mockito.never()).searchKnowledge(Mockito.anyString());
    }

    @Test
    void sunshineReActAgent_builderDoesNotSetMemory() throws Exception {
        Method agentMethod = AgentConfig.class.getDeclaredMethod(
                "sunshineReActAgent", Toolkit.class, ProcessingStepHook.class);
        assertThat(agentMethod.getReturnType()).isEqualTo(io.agentscope.core.ReActAgent.class);
        assertThat(agentMethod.getParameterCount()).isEqualTo(2);
    }
}
