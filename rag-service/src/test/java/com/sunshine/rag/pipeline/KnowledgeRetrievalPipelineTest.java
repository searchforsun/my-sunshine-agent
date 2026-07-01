package com.sunshine.rag.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.config.EffectiveConfigService;
import com.sunshine.rag.admin.config.EffectiveRagConfig;
import com.sunshine.rag.config.RagChunkProperties;
import com.sunshine.rag.config.RagRewriteProperties;
import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.config.RagSearchProperties;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeRetrievalPipelineTest {
    @Mock
    private RetrievalService retrievalService;
    @Mock
    private QueryRewritePipeline queryRewritePipeline;
    @Mock
    private EffectiveConfigService effectiveConfigService;

    private KnowledgeRetrievalPipeline pipeline;
    private EffectiveRagConfig effectiveConfig;

    @BeforeEach
    void setUp() {
        RagRewriteProperties props = new RagRewriteProperties();
        RagRewriteProperties.Timeline timeline = new RagRewriteProperties.Timeline();
        timeline.setRag("优化检索词");
        timeline.setHyde("生成参考文档");
        timeline.setEmptyRecall("换种方式再查");
        props.setTimeline(timeline);
        RagSearchProperties search = new RagSearchProperties();
        RagRerankProperties rerank = new RagRerankProperties();
        RagChunkProperties chunk = new RagChunkProperties();
        effectiveConfig = EffectiveRagConfig.fromNacos(search, rerank, chunk);
        when(effectiveConfigService.resolve(anyString(), anyString())).thenReturn(effectiveConfig);
        pipeline = new KnowledgeRetrievalPipeline(
                retrievalService, queryRewritePipeline, props, effectiveConfigService);
    }

    private static PipelineSearchRequest req(String query) {
        return PipelineSearchRequest.of(query, 3, "default", "default", "hybrid+rerank", true, false);
    }

    @Test
    void searchReturnsFirstHitsWithoutEmptyRecall() {
        List<RetrievalService.DocFragment> hits = List.of(new RetrievalService.DocFragment("A", "c", 0.9f));
        when(queryRewritePipeline.isRagEnabled()).thenReturn(false);
        when(retrievalService.search("q", 3, "hybrid+rerank", "default", "default", effectiveConfig)).thenReturn(Mono.just(hits));
        PipelineSearchResult result = pipeline.search(req("q")).block();
        assertThat(result).isNotNull();
        assertThat(result.results()).isEqualTo(hits);
        verify(queryRewritePipeline, never()).rewriteEmptyRecall(anyString());
    }

    @Test
    void searchAppliesRagRewriteBeforeRetrieval() {
        when(queryRewritePipeline.isRagEnabled()).thenReturn(true);
        when(queryRewritePipeline.rewriteForRag("口语问"))
                .thenReturn(QueryRewriteOutcome.of("rag", "口语问", "公司报销管理制度 差旅报销", 1L));
        when(retrievalService.search("公司报销管理制度 差旅报销", 3, "hybrid+rerank", "default", "default", effectiveConfig))
                .thenReturn(Mono.just(List.of(new RetrievalService.DocFragment("公司报销管理制度", "content", 0.8f))));
        PipelineSearchResult result = pipeline.search(req("口语问")).block();
        assertThat(result).isNotNull();
        assertThat(result.results()).hasSize(1);
        assertThat(result.effectiveQuery()).isEqualTo("公司报销管理制度 差旅报销");
    }

    @Test
    void searchRetriesWhenEmptyAndRewriteEnabled() {
        when(queryRewritePipeline.isRagEnabled()).thenReturn(false);
        when(queryRewritePipeline.isHydeEnabled()).thenReturn(false);
        when(retrievalService.search("口语问", 3, "hybrid+rerank", "default", "default", effectiveConfig))
                .thenReturn(Mono.just(List.of()));
        when(queryRewritePipeline.isEmptyRecallEnabled()).thenReturn(true);
        when(queryRewritePipeline.rewriteEmptyRecall("口语问"))
                .thenReturn(new QueryRewritePipeline.EmptyRecallRewrite(
                        List.of("公司报销管理制度 差旅报销"),
                        QueryRewriteOutcome.emptyRecall("口语问", List.of("公司报销管理制度 差旅报销"), 1L)));
        when(retrievalService.search("公司报销管理制度 差旅报销", 3, "hybrid+rerank", "default", "default", effectiveConfig))
                .thenReturn(Mono.just(List.of(new RetrievalService.DocFragment("公司报销管理制度", "content", 0.8f))));
        PipelineSearchResult result = pipeline.search(req("口语问")).block();
        assertThat(result).isNotNull();
        assertThat(result.results().get(0).docName()).isEqualTo("公司报销管理制度");
    }

    @Test
    void mergeFragmentsDedupesAndSorts() {
        List<RetrievalService.DocFragment> merged = KnowledgeRetrievalPipeline.mergeFragments(List.of(
                List.of(new RetrievalService.DocFragment("A", "x", 0.5f), new RetrievalService.DocFragment("B", "y", 0.9f)),
                List.of(new RetrievalService.DocFragment("A", "x", 0.7f))
        ), 3);
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).docName()).isEqualTo("B");
        assertThat(merged.get(1).score()).isEqualTo(0.7f);
    }

    @Test
    void searchUsesHydeAsFallbackAfterFirstSearchEmpty() {
        when(queryRewritePipeline.isRagEnabled()).thenReturn(true);
        when(queryRewritePipeline.isHydeEnabled()).thenReturn(true);
        when(queryRewritePipeline.rewriteForRag("报差旅"))
                .thenReturn(QueryRewriteOutcome.of("rag", "报差旅", "公司差旅费报销管理办法", 1L));
        when(retrievalService.search("公司差旅费报销管理办法", 3, "hybrid+rerank", "default", "default", effectiveConfig))
                .thenReturn(Mono.just(List.of()));
        when(queryRewritePipeline.hydeForRag("报差旅"))
                .thenReturn(QueryRewriteOutcome.of("hyde", "报差旅", "员工出差应提交差旅审批单并附发票", 2L));
        when(retrievalService.search("员工出差应提交差旅审批单并附发票", 3, "hybrid+rerank", "default", "default", effectiveConfig))
                .thenReturn(Mono.just(List.of(new RetrievalService.DocFragment("公司差旅费报销管理办法", "content", 0.8f))));
        PipelineSearchResult result = pipeline.search(req("报差旅")).block();
        assertThat(result).isNotNull();
        assertThat(result.results()).hasSize(1);
        verify(queryRewritePipeline).hydeForRag("报差旅");
    }

    @Test
    void searchSkipsHydeWhenFirstSearchHits() {
        when(queryRewritePipeline.isRagEnabled()).thenReturn(true);
        when(queryRewritePipeline.rewriteForRag("报差旅"))
                .thenReturn(QueryRewriteOutcome.of("rag", "报差旅", "公司差旅费报销管理办法", 1L));
        when(retrievalService.search("公司差旅费报销管理办法", 3, "hybrid+rerank", "default", "default", effectiveConfig))
                .thenReturn(Mono.just(List.of(new RetrievalService.DocFragment("公司差旅费报销管理办法", "content", 0.8f))));
        PipelineSearchResult result = pipeline.search(req("报差旅")).block();
        assertThat(result).isNotNull();
        assertThat(result.results()).hasSize(1);
        verify(queryRewritePipeline, never()).hydeForRag(anyString());
    }

    @Test
    void searchIncludesTraceWhenRequested() {
        when(queryRewritePipeline.isRagEnabled()).thenReturn(true);
        when(queryRewritePipeline.rewriteForRag("q"))
                .thenReturn(QueryRewriteOutcome.of("rag", "q", "优化q", 5L));
        when(retrievalService.search("优化q", 3, "hybrid+rerank", "default", "default", effectiveConfig))
                .thenReturn(Mono.just(List.of()));
        when(queryRewritePipeline.isHydeEnabled()).thenReturn(false);
        when(queryRewritePipeline.isEmptyRecallEnabled()).thenReturn(false);
        PipelineSearchRequest traced = PipelineSearchRequest.of("q", 3, "default", "default", "hybrid+rerank", true, true);
        PipelineSearchResult result = pipeline.search(traced).block();
        assertThat(result).isNotNull();
        assertThat(result.trace()).isNotNull();
        assertThat(result.trace().searchCount()).isEqualTo(1);
        assertThat(result.trace().stages()).isNotEmpty();
        assertThat(result.trace().stages().get(0).scenarioLabel()).isEqualTo("优化检索词");
    }
}
