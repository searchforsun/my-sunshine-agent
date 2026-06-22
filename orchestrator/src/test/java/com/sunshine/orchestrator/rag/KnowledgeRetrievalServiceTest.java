package com.sunshine.orchestrator.rag;

import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.config.RagSearchProperties;
import com.sunshine.orchestrator.rewrite.QueryRewriteOutcome;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {

    @Mock
    private RagClient ragClient;
    @Mock
    private QueryRewriteService queryRewriteService;

    private KnowledgeRetrievalService service;
    private RagSearchProperties props;

    @BeforeEach
    void setUp() {
        props = new RagSearchProperties();
        props.setStrategy("hybrid+rerank");
        service = new KnowledgeRetrievalService(ragClient, props, queryRewriteService);
    }

    @Test
    void searchReturnsFirstHitsWithoutRewrite() {
        List<RagClient.RagHit> hits = List.of(new RagClient.RagHit("A", "c", 0.9f));
        when(ragClient.search("q", 3, "hybrid+rerank")).thenReturn(Mono.just(hits));
        assertThat(service.search("q", 3)).isEqualTo(hits);
        verify(queryRewriteService, never()).rewriteEmptyRecall(anyString());
    }

    @Test
    void searchAppliesRagRewriteBeforeRetrieval() {
        when(queryRewriteService.isRagEnabled()).thenReturn(true);
        when(queryRewriteService.rewriteForRag(eq("口语问"), isNull()))
                .thenReturn(QueryRewriteOutcome.of("rag", "口语问", "公司报销管理制度 差旅报销", 1L));
        when(ragClient.search("公司报销管理制度 差旅报销", 3, "hybrid+rerank"))
                .thenReturn(Mono.just(List.of(new RagClient.RagHit("公司报销管理制度", "content", 0.8f))));
        List<RagClient.RagHit> out = service.search("口语问", 3);
        assertThat(out).hasSize(1);
        verify(ragClient).search("公司报销管理制度 差旅报销", 3, "hybrid+rerank");
    }

    @Test
    void searchRetriesWhenEmptyAndRewriteEnabled() {
        when(ragClient.search(eq("口语问"), anyInt(), anyString()))
                .thenReturn(Mono.just(List.of()));
        when(queryRewriteService.isEmptyRecallEnabled()).thenReturn(true);
        when(queryRewriteService.rewriteEmptyRecall(eq("口语问"), isNull()))
                .thenReturn(new QueryRewriteService.EmptyRecallRewrite(
                        List.of("公司报销管理制度 差旅报销"),
                        QueryRewriteOutcome.emptyRecall("口语问", List.of("公司报销管理制度 差旅报销"), 1L)));
        when(ragClient.search("公司报销管理制度 差旅报销", 3, "hybrid+rerank"))
                .thenReturn(Mono.just(List.of(new RagClient.RagHit("公司报销管理制度", "content", 0.8f))));
        List<RagClient.RagHit> out = service.search("口语问", 3);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).docName()).isEqualTo("公司报销管理制度");
    }

    @Test
    void mergeHitsDedupesAndSorts() {
        List<RagClient.RagHit> merged = KnowledgeRetrievalService.mergeHits(List.of(
                List.of(new RagClient.RagHit("A", "x", 0.5f), new RagClient.RagHit("B", "y", 0.9f)),
                List.of(new RagClient.RagHit("A", "x", 0.7f))
        ), 3);
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).docName()).isEqualTo("B");
        assertThat(merged.get(1).score()).isEqualTo(0.7f);
    }

    @Test
    void searchRetriesWithOriginalQueryWhenRagAndHydeEmpty() {
        when(queryRewriteService.isRagEnabled()).thenReturn(true);
        when(queryRewriteService.isHydeEnabled()).thenReturn(true);
        when(queryRewriteService.rewriteForRag(eq("口语问"), isNull()))
                .thenReturn(QueryRewriteOutcome.of("rag", "口语问", "公司报销管理制度 差旅报销", 1L));
        when(queryRewriteService.hydeForRag(eq("口语问"), isNull()))
                .thenReturn(QueryRewriteOutcome.of("hyde", "口语问", "员工出差应提交审批单", 2L));
        when(ragClient.search("员工出差应提交审批单", 3, "hybrid+rerank"))
                .thenReturn(Mono.just(List.of()));
        when(queryRewriteService.isEmptyRecallEnabled()).thenReturn(true);
        when(queryRewriteService.rewriteEmptyRecall(eq("口语问"), isNull()))
                .thenReturn(new QueryRewriteService.EmptyRecallRewrite(
                        List.of("差旅费报销管理办法 打车"),
                        QueryRewriteOutcome.emptyRecall("口语问", List.of("差旅费报销管理办法 打车"), 1L)));
        when(ragClient.search("差旅费报销管理办法 打车", 3, "hybrid+rerank"))
                .thenReturn(Mono.just(List.of(new RagClient.RagHit("差旅费管理办法", "content", 0.8f))));
        List<RagClient.RagHit> out = service.search("口语问", 3);
        assertThat(out).hasSize(1);
        verify(queryRewriteService).rewriteEmptyRecall(eq("口语问"), isNull());
    }

    @Test
    void searchUsesHydeDocumentWhenEnabled() {
        when(queryRewriteService.isRagEnabled()).thenReturn(true);
        when(queryRewriteService.isHydeEnabled()).thenReturn(true);
        when(queryRewriteService.rewriteForRag(eq("报差旅"), isNull()))
                .thenReturn(QueryRewriteOutcome.of("rag", "报差旅", "公司差旅费报销管理办法", 1L));
        when(queryRewriteService.hydeForRag(eq("报差旅"), isNull()))
                .thenReturn(QueryRewriteOutcome.of("hyde", "报差旅", "员工出差应提交差旅审批单并附发票", 2L));
        when(ragClient.search("员工出差应提交差旅审批单并附发票", 3, "hybrid+rerank"))
                .thenReturn(Mono.just(List.of(new RagClient.RagHit("公司差旅费报销管理办法", "content", 0.8f))));
        List<RagClient.RagHit> out = service.search("报差旅", 3);
        assertThat(out).hasSize(1);
        verify(ragClient).search("员工出差应提交差旅审批单并附发票", 3, "hybrid+rerank");
        verify(ragClient, never()).search("公司差旅费报销管理办法", 3, "hybrid+rerank");
    }
}
