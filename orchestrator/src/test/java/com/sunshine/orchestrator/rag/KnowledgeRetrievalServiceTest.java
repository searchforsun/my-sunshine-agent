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
class KnowledgeRetrievalServiceTest {
    @Mock
    private RagClient ragClient;

    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeRetrievalService(ragClient);
    }

    @Test
    void searchReturnsHitsFromPipeline() {
        List<RagClient.RagHit> hits = List.of(new RagClient.RagHit("A", "c", 0.9f));
        when(ragClient.searchKnowledge("q", null, "default", "default", null, false))
                .thenReturn(Mono.just(new RagClient.RagSearchResult(hits, "q", List.of())));
        assertThat(service.search("q")).isEqualTo(hits);
    }

    @Test
    void searchPassesTenantAndTraceFlag() {
        when(ragClient.searchKnowledge("q", null, "tenant-a", "default", null, true))
                .thenReturn(Mono.just(new RagClient.RagSearchResult(List.of(), "q", List.of())));
        service.search("q", "tenant-a", "msg-1");
        verify(ragClient).searchKnowledge("q", null, "tenant-a", "default", null, true);
    }

    @Test
    void searchMonoPassesExplicitTopKOverride() {
        when(ragClient.searchKnowledge(eq("q"), eq(5), eq("default"), eq("default"), isNull(), eq(false)))
                .thenReturn(Mono.just(new RagClient.RagSearchResult(List.of(), "q", List.of())));
        service.searchMono("q", 5).block();
        verify(ragClient).searchKnowledge("q", 5, "default", "default", null, false);
    }
}
