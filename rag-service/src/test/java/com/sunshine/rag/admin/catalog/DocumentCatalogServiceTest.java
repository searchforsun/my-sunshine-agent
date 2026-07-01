package com.sunshine.rag.admin.catalog;

import com.sunshine.rag.admin.catalog.dto.IngestTextRequest;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.entity.DocumentVersionEntity;
import com.sunshine.rag.entity.KnowledgeBaseEntity;
import com.sunshine.rag.parser.MarkdownParser;
import com.sunshine.rag.repository.DocumentRepository;
import com.sunshine.rag.repository.DocumentVersionRepository;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.EmbeddingService;
import com.sunshine.rag.service.MilvusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentCatalogServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentVersionRepository documentVersionRepository;
    @Mock
    private MarkdownParser markdownParser;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private MilvusService milvusService;
    @Mock
    private ElasticsearchIndexService elasticsearchIndexService;

    private DocumentCatalogService service;

    @BeforeEach
    void setUp() {
        service = new DocumentCatalogService(
                knowledgeBaseService,
                documentRepository,
                documentVersionRepository,
                markdownParser,
                embeddingService,
                milvusService,
                elasticsearchIndexService);
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setTenantId("default");
        kb.setKbId("default");
        when(knowledgeBaseService.requireKb(anyString(), anyString())).thenReturn(kb);
    }

    @Test
    void secondIngestSupersedesFirstVersion() {
        when(embeddingService.embed(anyString())).thenReturn(Mono.just(List.of(0.1f, 0.2f)));
        String contentV1 = "# 报销制度\nv1";
        String contentV2 = "# 报销制度\nv2";
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId("default");
        doc.setKbId("default");
        doc.setDocId("报销制度");
        doc.setDisplayName("报销制度");
        when(documentRepository.findByTenantIdAndKbIdAndDocId("default", "default", "报销制度"))
                .thenReturn(Optional.of(doc));
        when(markdownParser.parse(contentV1)).thenReturn(List.of("v1"));
        when(markdownParser.parse(contentV2)).thenReturn(List.of("v2"));
        when(documentVersionRepository.findByTenantIdAndKbIdAndDocIdAndStatus("default", "default", "报销制度", "active"))
                .thenReturn(new ArrayList<>())
                .thenReturn(List.of(activeVersion(1, "v1")));
        when(documentVersionRepository.findByTenantIdAndKbIdAndDocIdOrderByVersionDesc("default", "default", "报销制度"))
                .thenReturn(List.of())
                .thenReturn(List.of(activeVersion(1, "v1")));
        when(documentVersionRepository.save(any(DocumentVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var first = service.ingestText("default", "default", new IngestTextRequest(contentV1, null, "报销制度", null)).block();
        assertThat(first).isNotNull();
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.chunks()).isEqualTo(1);

        var second = service.ingestText("default", "default", new IngestTextRequest(contentV2, null, "报销制度", null)).block();
        assertThat(second).isNotNull();
        assertThat(second.version()).isEqualTo(2);

        verify(milvusService).deleteByDocVersion("default", "default", "报销制度", 1);
        verify(elasticsearchIndexService).deleteByDocVersion("default", "default", "报销制度", 1);
        verify(milvusService, times(2)).insert(any());
        verify(milvusService, never()).deleteByDocVersion("default", "default", "报销制度", 2);
    }

    @Test
    void supersedeVersionMarksOldChunksInactive() {
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId("default");
        doc.setKbId("default");
        doc.setDocId("doc-a");
        when(documentRepository.findByTenantIdAndKbIdAndDocId("default", "default", "doc-a"))
                .thenReturn(Optional.of(doc));
        DocumentVersionEntity version = activeVersion(1, "content");
        version.setDocId("doc-a");
        when(documentVersionRepository.findByTenantIdAndKbIdAndDocIdAndVersion("default", "default", "doc-a", 1))
                .thenReturn(Optional.of(version));
        when(documentVersionRepository.save(any(DocumentVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.supersedeVersion("default", "default", "doc-a", 1);

        ArgumentCaptor<DocumentVersionEntity> captor = ArgumentCaptor.forClass(DocumentVersionEntity.class);
        verify(documentVersionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("superseded");
        verify(milvusService).deleteByDocVersion("default", "default", "doc-a", 1);
    }

    private static DocumentVersionEntity activeVersion(int version, String markdown) {
        DocumentVersionEntity entity = new DocumentVersionEntity();
        entity.setTenantId("default");
        entity.setKbId("default");
        entity.setDocId("报销制度");
        entity.setVersion(version);
        entity.setStatus("active");
        entity.setParsedMarkdown(markdown);
        entity.setChunkCount(1);
        return entity;
    }
}
