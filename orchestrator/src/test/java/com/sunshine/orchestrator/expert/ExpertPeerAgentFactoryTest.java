package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.agent.DynamicToolkitFactory;
import com.sunshine.orchestrator.agent.ReActAgentFactory;
import com.sunshine.orchestrator.agent.runtime.AgentRole;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.TimelineBinding;
import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.memory.MemoryProperties;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertPeerAgentFactoryTest {

    @Mock DynamicToolkitFactory dynamicToolkitFactory;
    @Mock ToolCatalogService toolCatalogService;
    @Mock ReActAgentFactory reactAgentFactory;

    ExpertPeerAgentFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ExpertPeerAgentFactory(
                dynamicToolkitFactory, toolCatalogService, reactAgentFactory, new MemoryProperties());
        ReflectionTestUtils.setField(factory, "modelName", "test-model");
        ReflectionTestUtils.setField(factory, "modelBaseUrl", "http://localhost:8300/v1");
        ReflectionTestUtils.setField(factory, "apiKey", "k");
        when(reactAgentFactory.composeSystemPrompt(org.mockito.ArgumentMatchers.any())).thenReturn("sys");
        when(reactAgentFactory.resolveMaxIters(org.mockito.ArgumentMatchers.any())).thenReturn(2);
        when(dynamicToolkitFactory.buildForSubAgent(anyList(), isNull(), isNull(), isNull())).thenReturn(new Toolkit());
    }

    @Test
    void emptyWhitelist_usesBuildForSubAgent_notFullBuild() {
        AgentRunRequest req = subWithTools(List.of());
        factory.create(req);
        verify(dynamicToolkitFactory).buildForSubAgent(eq(List.of()), isNull(), isNull(), isNull());
        verify(dynamicToolkitFactory, never()).build(anyString());
    }

    @Test
    void concreteWhitelist_usesBuildForSubAgent() {
        AgentRunRequest req = subWithTools(List.of("sdk__a__t1"));
        factory.create(req);
        verify(dynamicToolkitFactory).buildForSubAgent(eq(List.of("sdk__a__t1")), isNull(), isNull(), isNull());
    }

    private static AgentRunRequest subWithTools(List<String> tools) {
        return new AgentRunRequest(
                AgentRole.SUB, "run-1", "parent",
                MemoryContext.forSubAgent(), "", List.of(),
                null, null, null, null, tools, "overlay", 2,
                TimelineBinding.SUB_COMPRESSED, false, null, null);
    }
}
