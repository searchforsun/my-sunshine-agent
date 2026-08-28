package com.sunshine.rag.service;

import com.sunshine.rag.config.ToolIndexProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 5.5 工具语义索引 ToolIndexService 单测：
 * 开关/全量重建（embed→replaceAll）/检索 minScore 过滤与降序/embed 失败降级/doc 拼接文本。
 */
class ToolIndexServiceTest {

    private ToolIndexProperties props(boolean enabled, float minScore, int defaultTopK) {
        ToolIndexProperties p = new ToolIndexProperties();
        p.setEnabled(enabled);
        p.setMinScore(minScore);
        p.setDefaultTopK(defaultTopK);
        return p;
    }

    private EmbeddingService embedding(Mono<List<Float>> result) {
        EmbeddingService e = mock(EmbeddingService.class);
        when(e.embed(any())).thenReturn(result);
        return e;
    }

    private static final List<Float> V1 = List.of(0.1f, 0.2f);

    @Test
    void disabled_skipsSyncAndSearch() {
        ToolMilvusService milvus = mock(ToolMilvusService.class);
        EmbeddingService embed = mock(EmbeddingService.class);
        ToolIndexService svc = new ToolIndexService(milvus, embed, props(false, 0.3f, 8));
        svc.sync("t1", List.of(new ToolIndexService.ToolIndexDoc("a", "A", "desc", ""))).block();
        verify(milvus, never()).replaceAll(any(), any());
        assertThat(svc.search("q", 5, "t1", null).block()).isEmpty();
    }

    @Test
    void sync_embedsEachDocAndRebuildsAll() {
        ToolMilvusService milvus = mock(ToolMilvusService.class);
        EmbeddingService embed = mock(EmbeddingService.class);
        when(embed.embed(any())).thenReturn(Mono.just(V1));
        ToolIndexService svc = new ToolIndexService(milvus, embed, props(true, 0.3f, 8));
        svc.sync("t1", List.of(
                new ToolIndexService.ToolIndexDoc("a", "A", "desc A", "p1"),
                new ToolIndexService.ToolIndexDoc("b", "B", "desc B", ""))).block();
        verify(embed).embed("A：desc A，参数：p1");
        verify(embed).embed("B：desc B");
        verify(milvus).replaceAll(org.mockito.ArgumentMatchers.eq("t1"), org.mockito.ArgumentMatchers.argThat(rows ->
                rows.size() == 2
                        && rows.get(0).toolId().equals("a")
                        && rows.get(0).embedding().equals(V1)
                        && rows.get(1).toolId().equals("b")));
    }

    @Test
    void sync_blankToolIdFilteredOut() {
        ToolMilvusService milvus = mock(ToolMilvusService.class);
        EmbeddingService embed = embedding(Mono.just(V1));
        ToolIndexService svc = new ToolIndexService(milvus, embed, props(true, 0.3f, 8));
        svc.sync("t1", List.of(
                new ToolIndexService.ToolIndexDoc("", "空", "desc", ""),
                new ToolIndexService.ToolIndexDoc("a", "A", "desc", ""))).block();
        verify(milvus).replaceAll(org.mockito.ArgumentMatchers.eq("t1"), org.mockito.ArgumentMatchers.argThat(rows ->
                rows.size() == 1 && rows.get(0).toolId().equals("a")));
    }

    @Test
    void sync_embedFailure_skipsSingleDoc() {
        ToolMilvusService milvus = mock(ToolMilvusService.class);
        EmbeddingService embed = mock(EmbeddingService.class);
        when(embed.embed(any())).thenReturn(Mono.error(new RuntimeException("llm down")));
        ToolIndexService svc = new ToolIndexService(milvus, embed, props(true, 0.3f, 8));
        svc.sync("t1", List.of(new ToolIndexService.ToolIndexDoc("a", "A", "desc", ""))).block();
        // 全部 embed 失败 → replaceAll 以空列表调用（删旧索引，防陈旧工具残留）
        verify(milvus).replaceAll("t1", List.of());
    }

    @Test
    void search_filtersByMinScoreAndSortsDesc() {
        ToolMilvusService milvus = mock(ToolMilvusService.class);
        EmbeddingService embed = mock(EmbeddingService.class);
        when(embed.embed("报销")).thenReturn(Mono.just(V1));
        when(milvus.search("t1", V1, 5)).thenReturn(List.of(
                new ToolMilvusService.ToolIndexHit("c", 0.55f),
                new ToolMilvusService.ToolIndexHit("a", 0.92f),
                new ToolMilvusService.ToolIndexHit("b", 0.2f)));
        ToolIndexService svc = new ToolIndexService(milvus, embed, props(true, 0.3f, 8));
        List<ToolIndexService.ToolIndexHit> hits = svc.search("报销", 5, "t1", 0.3f).block();
        assertThat(hits).extracting(ToolIndexService.ToolIndexHit::toolId)
                .containsExactly("a", "c");
        assertThat(hits.get(0).score()).isEqualTo(0.92f);
    }

    @Test
    void search_blankQuery_returnsEmptyWithoutEmbed() {
        ToolMilvusService milvus = mock(ToolMilvusService.class);
        EmbeddingService embed = mock(EmbeddingService.class);
        ToolIndexService svc = new ToolIndexService(milvus, embed, props(true, 0.3f, 8));
        assertThat(svc.search("   ", 5, "t1", null).block()).isEmpty();
    }

    @Test
    void doc_embeddingTextJoinsNameDescParams() {
        ToolIndexService.ToolIndexDoc doc =
                new ToolIndexService.ToolIndexDoc("a", "报销提交", "提交报销单并跟踪审批状态", "amount(number) 必填");
        assertThat(doc.embeddingText()).isEqualTo("报销提交：提交报销单并跟踪审批状态，参数：amount(number) 必填");
        assertThat(new ToolIndexService.ToolIndexDoc("a", null, null, null).embeddingText()).isEmpty();
    }
}
