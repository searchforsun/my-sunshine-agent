package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.AgentCatalogEntry;
import com.sunshine.orchestrator.client.ExternalAgentClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.context.AssembledContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentExecutorRouterTest {

    @Mock
    private AgentRuntime agentRuntime;
    @Mock
    private ExternalAgentClient externalAgentClient;

    private AgentExecutorRouter router;

    @BeforeEach
    void setUp() {
        router = new AgentExecutorRouter(agentRuntime, externalAgentClient);
    }

    private AgentRunRequest internalRequest() {
        return AgentRunRequest.sub(
                AssembledContext.empty(), "query", List.of(), "user-1", "default");
    }

    @Test
    void nullAgent_goesToAgentRuntime() {
        when(agentRuntime.run(any())).thenReturn(Flux.empty());
        router.dispatch(null, internalRequest(), "query", List.of());
        verify(agentRuntime).run(any());
        verifyNoInteractions(externalAgentClient);
    }

    @Test
    void internalAgent_goesToAgentRuntime() {
        when(agentRuntime.run(any())).thenReturn(Flux.just(StreamToken.content("内部")));
        AgentCatalogEntry internal = entry("policy-agent", "INTERNAL");
        var out = router.dispatch(internal, internalRequest(), "query", List.of())
                .map(StreamToken::text)
                .blockFirst();
        verify(agentRuntime).run(any());
        verifyNoInteractions(externalAgentClient);
        assertThat(out).isEqualTo("内部");
    }

    @Test
    void externalAgent_goesToA2aClient() {
        when(externalAgentClient.invoke(any(), any(), any()))
                .thenReturn(Flux.just(StreamToken.content("外部")));
        AgentCatalogEntry external = entry("external-legal", "EXTERNAL");
        var out = router.dispatch(external, internalRequest(), "query", List.of("ctx"))
                .map(StreamToken::text)
                .blockFirst();
        verify(externalAgentClient).invoke(external, "query", List.of("ctx"));
        verifyNoInteractions(agentRuntime);
        assertThat(out).isEqualTo("外部");
    }

    private static AgentCatalogEntry entry(String id, String source) {
        return new AgentCatalogEntry(
                id, "展示名", "desc", null, List.of(), List.of(), "[]", true, "default",
                List.of(), null, "{}", "{}", 2, 5,
                AgentCatalogEntry.AgentSource.valueOf(source), null, null, null);
    }
}
