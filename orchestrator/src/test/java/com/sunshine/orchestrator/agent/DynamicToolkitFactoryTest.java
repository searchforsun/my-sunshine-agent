package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.agent.remote.GenericRemoteToolFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

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
}
