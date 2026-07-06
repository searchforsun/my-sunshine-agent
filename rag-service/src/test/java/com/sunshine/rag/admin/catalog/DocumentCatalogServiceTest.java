package com.sunshine.rag.admin.catalog;

import com.sunshine.rag.admin.config.EffectiveConfigResolver;
import com.sunshine.rag.admin.catalog.dto.IngestTextRequest;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.entity.DocumentVersionEntity;
import com.sunshine.rag.entity.KnowledgeBaseEntity;
import com.sunshine.rag.admin.catalog.parser.DocumentFileParser;
import com.sunshine.rag.parser.MarkdownParser;
import com.sunshine.rag.repository.DocumentRepository;
import com.sunshine.rag.repository.DocumentVersionRepository;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.EmbeddingService;
import com.sunshine.rag.service.MilvusService;
import com.sunshine.rag.util.DocumentVersionTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentCatalogServiceTest {

    private static final String V1 = "20260701110011";
    private static final String V2 = "20260701120022";

    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private EffectiveConfigResolver effectiveConfigResolver;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentVersionRepository documentVersionRepository;
    @Mock
    private MarkdownParser markdownParser;
    @Mock
    private DocumentFileParser documentFileParser;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private MilvusService milvusService;
    @Mock
    private ElasticsearchIndexService elasticsearchIndexService;
    @Mock
    private com.sunshine.rag.storage.RagStorageFacade ragStorageFacade;
    @Mock
    private com.sunshine.rag.repository.IngestJobRepository ingestJobRepository;
    @Mock
    private com.sunshine.rag.client.DesensitizeClient desensitizeClient;

    private DocumentCatalogService service;

    @BeforeEach
    void setUp() {
        service = new DocumentCatalogService(
                knowledgeBaseService,
                effectiveConfigResolver,
                documentRepository,
                documentVersionRepository,
                ingestJobRepository,
                markdownParser,
                documentFileParser,
                embeddingService,
                milvusService,
                elasticsearchIndexService,
                ragStorageFacade,
                desensitizeClient);
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setTenantId("default");
        kb.setKbId("default");
        when(knowledgeBaseService.requireKb(anyString(), anyString())).thenReturn(kb);
        when(desensitizeClient.scrubForPublish(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(effectiveConfigResolver.resolve(anyString(), anyString()))
                .thenReturn(com.sunshine.rag.admin.config.ConfigBundlePayload.toResolvedKbConfig(
                        com.sunshine.rag.admin.config.ConfigBundleTestFixtures.fullPayload()));
        when(ragStorageFacade.documentContentRef(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("minio://bucket/key");
        service.self = service;
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
        when(markdownParser.parse(eq(contentV1), anyInt())).thenReturn(List.of("v1"));
        when(markdownParser.parse(eq(contentV2), anyInt())).thenReturn(List.of("v2"));
        when(documentVersionRepository.findByTenantIdAndKbIdAndDocIdAndStatus("default", "default", "报销制度", "active"))
                .thenReturn(new ArrayList<>())
                .thenReturn(List.of(activeVersion(V1, "v1")));
        when(documentVersionRepository.findByTenantIdAndKbIdAndDocIdOrderByVersionDesc("default", "default", "报销制度"))
                .thenReturn(new ArrayList<>())
                .thenReturn(List.of(activeVersion(V1, "v1")));
        when(documentVersionRepository.save(any(DocumentVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var first = service.ingestText("default", "default", new IngestTextRequest(contentV1, null, "报销制度", null)).block();
        assertThat(first).isNotNull();
        assertThat(first.version()).matches("\\d{14}");
        assertThat(first.chunks()).isEqualTo(1);

        when(documentVersionRepository.findByTenantIdAndKbIdAndDocIdAndStatus("default", "default", "报销制度", "active"))
                .thenReturn(List.of(activeVersion(V1, "v1")));

        var second = service.ingestText("default", "default", new IngestTextRequest(contentV2, null, "报销制度", null)).block();
        assertThat(second).isNotNull();
        assertThat(second.version()).matches("\\d{14}");
        assertThat(second.version()).isNotEqualTo(V1);

        verify(milvusService).deleteByDocVersion("default", "default", "报销制度", V1);
        verify(elasticsearchIndexService).deleteByDocVersion("default", "default", "报销制度", V1);
        verify(milvusService, times(2)).insert(any());
    }

    @Test
    void supersedeVersionMarksOldChunksInactive() {
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId("default");
        doc.setKbId("default");
        doc.setDocId("doc-a");
        when(documentRepository.findByTenantIdAndKbIdAndDocId("default", "default", "doc-a"))
                .thenReturn(Optional.of(doc));
        DocumentVersionEntity version = activeVersion(V1, "content");
        version.setDocId("doc-a");
        when(documentVersionRepository.findByTenantIdAndKbIdAndDocIdAndVersion("default", "default", "doc-a", V1))
                .thenReturn(Optional.of(version));
        when(documentVersionRepository.save(any(DocumentVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.supersedeVersion("default", "default", "doc-a", V1);

        ArgumentCaptor<DocumentVersionEntity> captor = ArgumentCaptor.forClass(DocumentVersionEntity.class);
        verify(documentVersionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("superseded");
        verify(milvusService).deleteByDocVersion("default", "default", "doc-a", V1);
    }

    @Test
    void documentVersionTimeFormat() {
        assertThat(DocumentVersionTime.fromInstant(java.time.Instant.parse("2026-07-01T03:00:11Z")))
                .matches("\\d{14}");
    }

    private static DocumentVersionEntity activeVersion(String version, String markdown) {
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
