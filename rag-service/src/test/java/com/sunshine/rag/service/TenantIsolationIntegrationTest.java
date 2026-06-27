package com.sunshine.rag.service;

import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.config.RagWebClientFactory;
import com.sunshine.rag.metrics.RagSearchMetrics;
import com.sunshine.rag.model.RetrievalCandidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 3.2.3 跨租户隔离：tenant-a 有语料时 tenant-b 检索 0 命中（vector + hybrid 路径）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantIsolationIntegrationTest {

    private static final String QUERY = "差旅报销管理办法";
    private static final RetrievalCandidate TENANT_A_HIT = new RetrievalCandidate(
            "travel#0", "差旅报销管理办法", "tenant-a 语料", 0.82f, RetrievalCandidate.SOURCE_VECTOR);
    private static final RetrievalCandidate TENANT_A_BM25 = new RetrievalCandidate(
            "travel#0", "差旅报销管理办法", "tenant-a 语料", 9.1f, RetrievalCandidate.SOURCE_BM25);

    @Mock
    private VectorSearchService vectorSearchService;
    @Mock
    private Bm25SearchService bm25SearchService;

    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        RagSearchProperties searchProps = new RagSearchProperties();
        searchProps.setMinScore(0.48f);
        searchProps.setHybridPoolSize(20);
        searchProps.setRrfK(60);
        RagRerankProperties rerankProps = new RagRerankProperties();
        rerankProps.setEnabled(false);
        HybridRetrievalService hybrid = new HybridRetrievalService(searchProps);
        RerankService rerank = new RerankService(
                rerankProps, new com.fasterxml.jackson.databind.ObjectMapper(),
                new RagSearchMetrics(new SimpleMeterRegistry()), new RagWebClientFactory());
        retrievalService = new RetrievalService(
                vectorSearchService, bm25SearchService, hybrid, rerank, searchProps, rerankProps,
                new RagSearchMetrics(new SimpleMeterRegistry()));
        stubTenantAwareSearch();
    }

    private void stubTenantAwareSearch() {
        when(vectorSearchService.search(eq(QUERY), anyInt(), anyBoolean(), eq("tenant-a")))
                .thenReturn(Mono.just(List.of(TENANT_A_HIT)));
        when(vectorSearchService.search(eq(QUERY), anyInt(), anyBoolean(), eq("tenant-b")))
                .thenReturn(Mono.just(List.of()));
        when(bm25SearchService.search(eq(QUERY), anyInt(), eq("tenant-a")))
                .thenReturn(Mono.just(List.of(TENANT_A_BM25)));
        when(bm25SearchService.search(eq(QUERY), anyInt(), eq("tenant-b")))
                .thenReturn(Mono.just(List.of()));
        when(bm25SearchService.isEnabled()).thenReturn(true);
    }

    @Test
    void vectorSearch_tenantBGetsZeroHitsWhenOnlyTenantAHasCorpus() {
        List<RetrievalService.DocFragment> tenantA = retrievalService
                .search(QUERY, 5, "vector", "tenant-a").block();
        List<RetrievalService.DocFragment> tenantB = retrievalService
                .search(QUERY, 5, "vector", "tenant-b").block();
        assertThat(tenantA).isNotEmpty();
        assertThat(tenantA.get(0).docName()).isEqualTo("差旅报销管理办法");
        assertThat(tenantB).isEmpty();
    }

    @Test
    void hybridSearch_tenantBGetsZeroHitsWhenOnlyTenantAHasCorpus() {
        List<RetrievalService.DocFragment> tenantA = retrievalService
                .search(QUERY, 5, "hybrid+rerank", "tenant-a").block();
        List<RetrievalService.DocFragment> tenantB = retrievalService
                .search(QUERY, 5, "hybrid+rerank", "tenant-b").block();
        assertThat(tenantA).isNotEmpty();
        assertThat(tenantB).isEmpty();
    }

    @Test
    void defaultTenantUsesDefaultId() {
        when(vectorSearchService.search(eq(QUERY), anyInt(), anyBoolean(), eq("default")))
                .thenReturn(Mono.just(List.of()));
        when(bm25SearchService.search(eq(QUERY), anyInt(), eq("default")))
                .thenReturn(Mono.just(List.of()));
        List<RetrievalService.DocFragment> hits = retrievalService.search(QUERY, 5, "vector").block();
        assertThat(hits).isEmpty();
    }
}
