package com.sunshine.rag.admin.debug;

import com.sunshine.rag.admin.config.EffectiveConfigService;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.config.RagChunkProperties;
import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.model.RetrievalCandidate;
import com.sunshine.rag.pipeline.KnowledgeRetrievalPipeline;
import com.sunshine.rag.pipeline.PipelineSearchRequest;
import com.sunshine.rag.service.RetrievalService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetrievalDebugServiceTest {

    @Mock
    private KnowledgeRetrievalPipeline knowledgeRetrievalPipeline;

    private RetrievalDebugService service;
    private EffectiveRagConfig config;

    @BeforeEach
    void setUp() {
        RagSearchProperties search = new RagSearchProperties();
        search.setDefaultTopK(3);
        RagRerankProperties rerank = new RagRerankProperties();
        RagChunkProperties chunk = new RagChunkProperties();
        config = EffectiveRagConfig.fromNacos(search, rerank, chunk);
        EffectiveConfigService effectiveConfigService = org.mockito.Mockito.mock(EffectiveConfigService.class);
        when(effectiveConfigService.resolve(any(), any())).thenReturn(config);
        service = new RetrievalDebugService(
                knowledgeRetrievalPipeline, effectiveConfigService, search, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void debugSearchReturnsHybridStagesInOrder() {
        RetrievalCandidate vectorHit = new RetrievalCandidate(
                "a#0", "报销制度", "餐费报销规则", 0.72f, RetrievalCandidate.SOURCE_VECTOR);
        RetrievalCandidate bm25Hit = new RetrievalCandidate(
                "a#0", "报销制度", "餐费报销规则", 9.1f, RetrievalCandidate.SOURCE_BM25);
        List<RetrievalDebugStage> stages = List.of(
                RetrievalDebugStage.retrieval("vector", List.of(vectorHit), null, 10),
                RetrievalDebugStage.retrieval("bm25", List.of(bm25Hit), null, 8),
                RetrievalDebugStage.retrieval("rrf", List.of(vectorHit.withSource(RetrievalCandidate.SOURCE_RRF)), null, 1),
                RetrievalDebugStage.retrieval("filter", List.of(vectorHit), null, 0));
        List<RetrievalService.DocFragment> finalHits = List.of(
                new RetrievalService.DocFragment("报销制度", "餐费报销规则", 0.72f));
        when(knowledgeRetrievalPipeline.debugSearch(any(PipelineSearchRequest.class), eq(config)))
                .thenReturn(Mono.just(new RetrievalDebugResult(stages, finalHits)));
        RetrievalDebugResult result = service.debugSearch("default", java.util.Map.of(
                "query", "报销怎么报",
                "kbId", "default",
                "includeRewrite", false)).block();
        assertThat(result).isNotNull();
        assertThat(result.stages()).extracting(RetrievalDebugStage::name)
                .containsExactly("vector", "bm25", "rrf", "filter");
        assertThat(result.stages().get(0).candidates().get(0).source()).isEqualTo(RetrievalCandidate.SOURCE_VECTOR);
        assertThat(result.finalResults()).hasSize(1);
    }
}
