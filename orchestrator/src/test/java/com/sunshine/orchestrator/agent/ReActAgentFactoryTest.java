package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.TimelineBinding;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.memory.MemoryProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
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
    private DynamicToolkitFactory dynamicToolkitFactory;
    @Mock
    private ProcessingStepHookFactory stepHookFactory;

    @Mock
    private Toolkit subToolkit;

    private PromptCatalogHolder catalogHolder;
    private MemoryProperties memoryProperties;
    private ReActAgentFactory factory;

    @BeforeEach
    void setUp() {
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();
        executionProperties.getReact().setMaxIters(5);
        memoryProperties = new MemoryProperties();
        catalogHolder = new PromptCatalogHolder();
        catalogHolder.replace(PromptCatalogSnapshot.of(1L, List.of(
                new PromptCatalogEntry("system-prompt", "system", "系统提示", true, 0, 1,
                        "base system", null))));
        factory = new ReActAgentFactory(
                catalogHolder, executionProperties, memoryProperties, dynamicToolkitFactory, stepHookFactory);
        ReflectionTestUtils.setField(factory, "modelName", "deepseek-v4-pro");
        ReflectionTestUtils.setField(factory, "modelBaseUrl", "http://localhost:8300/v1");
        ReflectionTestUtils.setField(factory, "apiKey", "test-key");
    }

    @Test
    void composeSystemPrompt_appendsOverlayWhenPresent() {
        AgentRunRequest req = subRequest(null, List.of("sdk__sunshine-finance__list_my_expenses"), "仅输出合规结论");
        assertThat(factory.composeSystemPrompt(req))
                .isEqualTo("base system\n\n仅输出合规结论");
    }

    @Test
    void composeSystemPrompt_skipsOverlayWhenBlank() {
        AgentRunRequest req = subRequest(null, List.of("sdk__sunshine-finance__list_my_expenses"), "  ");
        assertThat(factory.composeSystemPrompt(req)).isEqualTo("base system");
    }

    @Test
    void resolveToolkit_subUsesExplicitWhitelist() {
        AgentRunRequest req = subRequest(null, List.of("sdk__sunshine-finance__list_my_expenses"), null);
        when(dynamicToolkitFactory.buildForSubAgent(
                List.of("sdk__sunshine-finance__list_my_expenses"), "default", null, "u1"))
                .thenReturn(subToolkit);

        assertThat(factory.resolveToolkit(req)).isSameAs(subToolkit);
        verify(dynamicToolkitFactory).buildForSubAgent(
                List.of("sdk__sunshine-finance__list_my_expenses"), "default", null, "u1");
    }

    @Test
    void resolveToolkit_subWithoutExtraToolsUsesSubAgentToolkit() {
        AgentRunRequest req = subRequest("compliance-check", null, null);
        when(dynamicToolkitFactory.buildForSubAgent(null, "default", "compliance-check", "u1")).thenReturn(subToolkit);

        assertThat(factory.resolveToolkit(req)).isSameAs(subToolkit);
        verify(dynamicToolkitFactory).buildForSubAgent(null, "default", "compliance-check", "u1");
    }

    @Test
    void resolveToolkit_mainBuildsFreshToolkitFromTenantToolSet() {
        AgentRunRequest req = AgentRunRequest.main(
                AssembledContext.empty(), "q", "u1", "default", "msg-main");
        when(dynamicToolkitFactory.build("default", null, "u1")).thenReturn(subToolkit);

        assertThat(factory.resolveToolkit(req)).isSameAs(subToolkit);
        verify(dynamicToolkitFactory).build("default", null, "u1");
    }

    @Test
    void resolveMaxIters_prefersRequestValue() {
        AgentRunRequest req = new AgentRunRequest(
                AgentRole.SUB, "run-1", null, AssembledContext.empty(), "q", List.of(),
                "u1", "default", null, null, List.of("sdk__sunshine-finance__list_my_expenses"), null, 4,
                TimelineBinding.SUB_COMPRESSED, false, null, null);
        assertThat(factory.resolveMaxIters(req)).isEqualTo(4);
    }

    @Test
    void resolveMaxIters_fallsBackToDefault() {
        AgentRunRequest req = subRequest(null, List.of("sdk__sunshine-finance__list_my_expenses"), null);
        assertThat(factory.resolveMaxIters(req)).isEqualTo(5);
    }

    @Test
    void createMemory_whenAutoContextEnabled_returnsAutoContextMemory() {
        memoryProperties.getAutoContext().setEnabled(true);
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey("k").modelName("m").baseUrl("http://localhost:8300/v1").stream(true).build();
        Memory memory = factory.createMemory(model);
        assertThat(memory).isInstanceOf(AutoContextMemory.class);
    }

    @Test
    void createMemory_whenAutoContextDisabled_returnsNull() {
        memoryProperties.getAutoContext().setEnabled(false);
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey("k").modelName("m").baseUrl("http://localhost:8300/v1").stream(true).build();
        assertThat(factory.createMemory(model)).isNull();
    }

    @Test
    void buildAutoContextConfig_mapsNacosTunables() {
        MemoryProperties.AutoContext ac = new MemoryProperties.AutoContext();
        ac.setMsgThreshold(40);
        ac.setLastKeep(12);
        ac.setMinConsecutiveToolMessages(4);
        ac.setMinCompressionTokenThreshold(3000);
        ac.setLargePayloadThreshold(5120);
        AutoContextConfig cfg = ReActAgentFactory.buildAutoContextConfig(ac);
        assertThat(cfg.getMsgThreshold()).isEqualTo(40);
        assertThat(cfg.getLastKeep()).isEqualTo(12);
        assertThat(cfg.getMinConsecutiveToolMessages()).isEqualTo(4);
        assertThat(cfg.getMinCompressionTokenThreshold()).isEqualTo(3000);
        assertThat(cfg.getLargePayloadThreshold()).isEqualTo(5120);
    }

    private static AgentRunRequest subRequest(String skillId, List<String> tools, String overlay) {
        return new AgentRunRequest(
                AgentRole.SUB,
                "run-sub",
                null,
                AssembledContext.empty(),
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
                false,
                null,
                null);
    }
}
