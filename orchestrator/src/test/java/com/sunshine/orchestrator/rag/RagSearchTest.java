package com.sunshine.orchestrator.rag;

import com.sunshine.orchestrator.client.RagClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagSearchTest {
    @Mock
    private RagClient ragClient;
    @Mock
    private DefaultKbResolver defaultKbResolver;

    @BeforeEach
    void setUp() {
    }

    @Test
    void searchBlockingReturnsHitsFromPipeline() {
        when(defaultKbResolver.resolve(eq("default"), isNull())).thenReturn(Mono.just("default"));
        List<RagClient.RagHit> hits = List.of(new RagClient.RagHit("A", "c", 0.9f));
        when(ragClient.searchKnowledge("q", null, "default", "default", null, false))
                .thenReturn(Mono.just(new RagClient.RagSearchResult(hits, "q", List.of())));
        assertThat(RagSearch.searchBlocking(ragClient, defaultKbResolver, "q", null, null, "default", null))
                .isEqualTo(hits);
    }

    @Test
    void searchBlockingPassesTenantAndTraceFlag() {
        when(defaultKbResolver.resolve("tenant-a", null)).thenReturn(Mono.just("finance"));
        when(ragClient.searchKnowledge("q", null, "tenant-a", "finance", null, true))
                .thenReturn(Mono.just(new RagClient.RagSearchResult(List.of(), "q", List.of())));
        RagSearch.searchBlocking(ragClient, defaultKbResolver, "q", null, null, "tenant-a", "msg-1");
        verify(ragClient).searchKnowledge("q", null, "tenant-a", "finance", null, true);
    }

    @Test
    void searchMonoPassesExplicitTopKOverride() {
        when(defaultKbResolver.resolve(eq("default"), isNull())).thenReturn(Mono.just("default"));
        when(ragClient.searchKnowledge(eq("q"), eq(5), eq("default"), eq("default"), isNull(), eq(false)))
                .thenReturn(Mono.just(new RagClient.RagSearchResult(List.of(), "q", List.of())));
        RagSearch.searchMono(ragClient, defaultKbResolver, "q", 5, null, "default", null).block();
        verify(ragClient).searchKnowledge("q", 5, "default", "default", null, false);
    }
}
