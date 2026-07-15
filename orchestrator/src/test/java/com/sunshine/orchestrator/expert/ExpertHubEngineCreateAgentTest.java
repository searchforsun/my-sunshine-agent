package com.sunshine.orchestrator.expert;

import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpertHubEngineCreateAgentTest {

    @Mock ToolSetResolver toolSetResolver;
    @Mock ExpertPeerAgentFactory expertPeerAgentFactory;
    @Mock com.sunshine.orchestrator.prompt.PromptComposer promptComposer;
    @Mock ExpertSpeakStreamer expertSpeakStreamer;
    @Mock com.sunshine.orchestrator.peer.PeerSynthesisProperties peerProperties;
    @Mock ExpertRoundCoordinatorService roundCoordinator;

    @InjectMocks ExpertHubEngine engine;

    @Test
    void star_expandsToEnabledPool() {
        when(toolSetResolver.resolveAllEnabledTools(isNull())).thenReturn(List.of("sdk__a__t1", "sdk__a__t2"));
        ExpertCatalogEntry e = entry("[\"*\"]");
        assertThat(engine.resolveToolWhitelist(e)).containsExactly("sdk__a__t1", "sdk__a__t2");
    }

    @Test
    void empty_staysEmpty() {
        assertThat(engine.resolveToolWhitelist(entry("[]"))).isEmpty();
    }

    @Test
    void concrete_passthrough() {
        assertThat(engine.resolveToolWhitelist(entry("[\"sdk__a__t1\"]")))
                .containsExactly("sdk__a__t1");
    }

    private static ExpertCatalogEntry entry(String toolsJson) {
        return new ExpertCatalogEntry("e1", "E", "d", "sys", List.of(), List.of(), toolsJson, true);
    }
}
