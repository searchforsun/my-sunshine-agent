package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.TimelineBinding;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.memory.MemoryContext;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentFactoryTest {

    @Mock
    private AgentPromptProperties prompts;
    @Mock
    private DynamicToolkitFactory dynamicToolkitFactory;
    @Mock
    private ProcessingStepHookFactory stepHookFactory;

    @Mock
    private Toolkit subToolkit;

    private ReActAgentFactory factory;

    @BeforeEach
    void setUp() {
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();
        executionProperties.getReact().setMaxIters(5);
        factory = new ReActAgentFactory(prompts, executionProperties, dynamicToolkitFactory, stepHookFactory);
        ReflectionTestUtils.setField(factory, "modelName", "deepseek-v4-pro");
        ReflectionTestUtils.setField(factory, "modelBaseUrl", "http://localhost:8300/v1");
        ReflectionTestUtils.setField(factory, "apiKey", "test-key");
    }

    @Test
    void composeSystemPrompt_appendsOverlayWhenPresent() {
        when(prompts.systemPromptOrEmpty()).thenReturn("base system");
        AgentRunRequest req = subRequest(null, List.of("sdk__sunshine-finance__list_finance_messages"), "仅输出合规结论");
        assertThat(factory.composeSystemPrompt(req))
                .isEqualTo("base system\n\n仅输出合规结论");
    }

    @Test
    void composeSystemPrompt_skipsOverlayWhenBlank() {
        when(prompts.systemPromptOrEmpty()).thenReturn("base system");
        AgentRunRequest req = subRequest(null, List.of("sdk__sunshine-finance__list_finance_messages"), "  ");
        assertThat(factory.composeSystemPrompt(req)).isEqualTo("base system");
    }

    @Test
    void resolveToolkit_subUsesExplicitWhitelist() {
        AgentRunRequest req = subRequest(null, List.of("sdk__sunshine-finance__list_finance_messages"), null);
        when(dynamicToolkitFactory.build(List.of("sdk__sunshine-finance__list_finance_messages"), "default"))
                .thenReturn(subToolkit);

        assertThat(factory.resolveToolkit(req)).isSameAs(subToolkit);
        verify(dynamicToolkitFactory).build(List.of("sdk__sunshine-finance__list_finance_messages"), "default");
    }

    @Test
    void resolveToolkit_mainBuildsFreshToolkitFromTenantToolSet() {
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-main");
        when(dynamicToolkitFactory.build("default")).thenReturn(subToolkit);

        assertThat(factory.resolveToolkit(req)).isSameAs(subToolkit);
        verify(dynamicToolkitFactory).build("default");
    }

    @Test
    void resolveMaxIters_prefersRequestValue() {
        AgentRunRequest req = new AgentRunRequest(
                AgentRole.SUB, "run-1", null, MemoryContext.empty(), "q", List.of(),
                "u1", "default", null, null, List.of("sdk__sunshine-finance__list_finance_messages"), null, 4,
                TimelineBinding.SUB_COMPRESSED, false);
        assertThat(factory.resolveMaxIters(req)).isEqualTo(4);
    }

    @Test
    void resolveMaxIters_fallsBackToDefault() {
        AgentRunRequest req = subRequest(null, List.of("sdk__sunshine-finance__list_finance_messages"), null);
        assertThat(factory.resolveMaxIters(req)).isEqualTo(5);
    }

    private static AgentRunRequest subRequest(String skillId, List<String> tools, String overlay) {
        return new AgentRunRequest(
                AgentRole.SUB,
                "run-sub",
                null,
                MemoryContext.empty(),
                "analyze",
                List.of(),
                "u1",
                "default",
                null,
                skillId,
                tools,
                overlay,
                0,
                TimelineBinding.SUB_COMPRESSED,
                false);
    }
}
