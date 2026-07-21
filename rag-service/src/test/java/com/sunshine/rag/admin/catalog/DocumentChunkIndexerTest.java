package com.sunshine.rag.admin.catalog;

import com.sunshine.rag.chunker.ChunkDraft;
import com.sunshine.rag.chunker.ChunkStrategy;
import com.sunshine.rag.model.ChunkInsertRequest;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.EmbeddingService;
import com.sunshine.rag.service.MilvusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentChunkIndexerTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private MilvusService milvusService;
    @Mock
    private ElasticsearchIndexService elasticsearchIndexService;

    private DocumentChunkIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new DocumentChunkIndexer(embeddingService, milvusService, elasticsearchIndexService);
        when(embeddingService.embed(anyString())).thenReturn(Mono.just(List.of(0.1f, 0.2f)));
    }

    @Test
    void embedAndIndexDrafts_parentChild_writesParentAndChildMetadata() {
        List<ChunkDraft> drafts = List.of(
                new ChunkDraft(0, "parent text", Map.of("level", "parent")),
                new ChunkDraft(1, "child snippet", Map.of("level", "child", "parentIndex", 0)));
        indexer.embedAndIndexDrafts(
                "default", "default", "doc-a", "Doc A", "20260701110011", drafts, ChunkStrategy.PARENT_CHILD)
                .block();

        ArgumentCaptor<ChunkInsertRequest> captor = ArgumentCaptor.forClass(ChunkInsertRequest.class);
        verify(milvusService, times(2)).insert(captor.capture());
        List<ChunkInsertRequest> inserts = captor.getAllValues();
        assertThat(inserts.get(0).chunkLevel()).isEqualTo("parent");
        assertThat(inserts.get(0).parentChunkId()).isEmpty();
        assertThat(inserts.get(0).strategy()).isEqualTo("parent_child");
        assertThat(inserts.get(1).chunkLevel()).isEqualTo("child");
        assertThat(inserts.get(1).parentChunkId()).isEqualTo("doc-a#v20260701110011#0");
        verify(elasticsearchIndexService, times(2)).indexChunk(
                anyString(), eq("Doc A"), anyString(), anyInt(), eq("default"), eq("default"),
                eq("doc-a"), eq("20260701110011"), eq("active"), eq("markdown"),
                eq("parent_child"), anyString(), anyString());
    }

    @Test
    void levelFromMeta_emptyWhenMissing() {
        assertThat(DocumentChunkIndexer.levelFromMeta(Map.of())).isEmpty();
        assertThat(DocumentChunkIndexer.levelFromMeta(Map.of("level", "child"))).isEqualTo("child");
    }

    @Test
    void embedAndIndex_wrapsPlainStringsAsMarkdownDrafts() {
        indexer.embedAndIndex("default", "default", "doc-a", "Doc A", "20260701110011", List.of("a", "b"))
                .block();
        ArgumentCaptor<ChunkInsertRequest> captor = ArgumentCaptor.forClass(ChunkInsertRequest.class);
        verify(milvusService, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(r -> "markdown".equals(r.strategy()));
    }
}
