package com.sunshine.orchestrator.agent;

import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * ReActAgent 不再依赖全局 InMemoryMemory；Toolkit 仍注册 RagTool。
 */
class SunshineAgentMemoryTest {

    @Test
    void toolkit_beanMethodRegistersRagTool() throws Exception {
        Method toolkitMethod = AgentConfig.class.getDeclaredMethod("toolkit", RagTool.class);
        assertThat(toolkitMethod.isAnnotationPresent(Bean.class)).isTrue();
        assertThat(Modifier.isPublic(toolkitMethod.getModifiers())).isTrue();

        AgentConfig config = new AgentConfig();
        RagTool ragTool = Mockito.mock(RagTool.class);

        Toolkit toolkit = config.toolkit(ragTool);

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
