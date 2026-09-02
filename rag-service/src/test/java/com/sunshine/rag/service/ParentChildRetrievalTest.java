package com.sunshine.rag.service;

import com.sunshine.rag.admin.config.ConfigBundlePayload;
import com.sunshine.rag.admin.config.ConfigBundleTestFixtures;
import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.config.RagSearchProperties;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParentChildRetrievalTest {

    private static final String PARENT_ID = "policy#v20260701110011#0";
    private static final String PARENT_TEXT = "完整父块：报销制度全文段落……";
    private static final String CHILD_TEXT = "子块：餐费上限 200 元";

    @Mock
    private VectorSearchService vectorSearchService;
    @Mock
    private Bm25SearchService bm25SearchService;
    @Mock
    private EffectiveConfigResolver effectiveConfigResolver;
    @Mock
    private ChunkContentLookup chunkContentLookup;

    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        RagSearchProperties searchProps = new RagSearchProperties();
        searchProps.setMinScore(0.1f);
        RagRerankProperties rerankProps = new RagRerankProperties();
        rerankProps.setEnabled(false);
        when(effectiveConfigResolver.resolve(any(), any()))
                .thenReturn(ConfigBundlePayload.toResolvedKbConfig(ConfigBundleTestFixtures.fullPayload()));
        retrievalService = new RetrievalService(
                vectorSearchService,
                bm25SearchService,
                new HybridRetrievalService(searchProps),
                new RerankService(
                        rerankProps,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        new RagSearchMetrics(new SimpleMeterRegistry()),
                        new com.sunshine.rag.config.RagWebClientFactory()),
                effectiveConfigResolver,
                searchProps,
                rerankProps,
                new RagSearchMetrics(new SimpleMeterRegistry()),
                chunkContentLookup);
    }

    @Test
    void childHit_expandsToParentContent() {
        RetrievalCandidate childHit = new RetrievalCandidate(
                "policy#v20260701110011#2",
                "报销制度",
                CHILD_TEXT,
                0.85f,
                RetrievalCandidate.SOURCE_VECTOR,
                RetrievalCandidate.LEVEL_CHILD,
                PARENT_ID);
        when(vectorSearchService.search(anyString(), anyInt(), anyBoolean(), anyString(), anyString(), anyFloat()))
                .thenReturn(Mono.just(List.of(childHit)));
        when(chunkContentLookup.fetchContent("default", "default", PARENT_ID)).thenReturn(PARENT_TEXT);

        List<RetrievalService.DocFragment> hits = retrievalService
                .search("餐费报销", 5, "vector", "default", "default",
                        ConfigBundlePayload.toResolvedKbConfig(ConfigBundleTestFixtures.fullPayload()).retrieval())
                .block();

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().content()).isEqualTo(PARENT_TEXT);
        assertThat(hits.getFirst().docName()).isEqualTo("报销制度");
    }

    @Test
    void multipleChildrenSameParent_dedupesToOneFragmentWithHighestScore() {
        RetrievalCandidate childLow = new RetrievalCandidate(
                "policy#v20260701110011#2",
                "报销制度",
                "子块 A",
                0.75f,
                RetrievalCandidate.SOURCE_VECTOR,
                RetrievalCandidate.LEVEL_CHILD,
                PARENT_ID);
        RetrievalCandidate childHigh = new RetrievalCandidate(
                "policy#v20260701110011#3",
                "报销制度",
                "子块 B",
                0.92f,
                RetrievalCandidate.SOURCE_VECTOR,
                RetrievalCandidate.LEVEL_CHILD,
                PARENT_ID);
        when(vectorSearchService.search(anyString(), anyInt(), anyBoolean(), anyString(), anyString(), anyFloat()))
                .thenReturn(Mono.just(List.of(childLow, childHigh)));
        when(chunkContentLookup.fetchContent("default", "default", PARENT_ID)).thenReturn(PARENT_TEXT);

        List<RetrievalService.DocFragment> hits = retrievalService
                .search("餐费报销", 5, "vector", "default", "default",
                        ConfigBundlePayload.toResolvedKbConfig(ConfigBundleTestFixtures.fullPayload()).retrieval())
                .block();

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().content()).isEqualTo(PARENT_TEXT);
        assertThat(hits.getFirst().score()).isEqualTo(0.92f);
    }

    @Test
    void plainChunkHit_keepsOwnContent() {
        RetrievalCandidate leaf = new RetrievalCandidate(
                "doc#v20260701110011#0", "文档", "plain chunk", 0.7f, RetrievalCandidate.SOURCE_VECTOR,
                "chunk", null);
        when(vectorSearchService.search(anyString(), anyInt(), anyBoolean(), anyString(), anyString(), anyFloat()))
                .thenReturn(Mono.just(List.of(leaf)));

        List<RetrievalService.DocFragment> hits = retrievalService
                .search("query", 5, "vector", "default", "default",
                        ConfigBundlePayload.toResolvedKbConfig(ConfigBundleTestFixtures.fullPayload()).retrieval())
                .block();

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().content()).isEqualTo("plain chunk");
    }
}
