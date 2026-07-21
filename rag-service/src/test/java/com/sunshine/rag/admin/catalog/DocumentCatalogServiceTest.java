package com.sunshine.rag.admin.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.dto.ChunkPreviewRequest;
import com.sunshine.rag.admin.catalog.dto.IngestTextRequest;
import com.sunshine.rag.chunker.ChunkDraft;
import com.sunshine.rag.chunker.ChunkParams;
import com.sunshine.rag.chunker.ChunkPreviewRecord;
import com.sunshine.rag.chunker.ChunkPreviewService;
import com.sunshine.rag.chunker.ChunkStrategy;
import com.sunshine.rag.entity.DocumentEntity;
import com.sunshine.rag.entity.DocumentVersionEntity;
import com.sunshine.rag.entity.KnowledgeBaseEntity;
import com.sunshine.rag.admin.catalog.parser.DocumentFileParser;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.repository.DocumentRepository;
import com.sunshine.rag.repository.DocumentVersionRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private DocumentRepository documentRepository;
    @Mock
    private DocumentVersionRepository documentVersionRepository;
    @Mock
    private DocumentFileParser documentFileParser;
    @Mock
    private com.sunshine.rag.storage.RagStorageFacade ragStorageFacade;
    @Mock
    private com.sunshine.rag.client.DesensitizeClient desensitizeClient;
    @Mock
    private DocumentChunkIndexer chunkIndexer;
    @Mock
    private DocumentVersionOps versionOps;
    @Mock
    private ChunkPreviewService chunkPreviewService;

    private DocumentCatalogService service;

    @BeforeEach
    void setUp() {
        service = new DocumentCatalogService(
                knowledgeBaseService,
                documentRepository,
                documentVersionRepository,
                documentFileParser,
                ragStorageFacade,
                desensitizeClient,
                chunkIndexer,
                versionOps,
                chunkPreviewService,
                new ObjectMapper());
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
        kb.setTenantId("default");
        kb.setKbId("default");
        when(knowledgeBaseService.requireKb(anyString(), anyString())).thenReturn(kb);
        when(desensitizeClient.scrubForPublish(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(ragStorageFacade.documentContentRef(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("minio://bucket/key");
        when(chunkIndexer.embedAndIndex(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Mono.empty());
        when(versionOps.newVersionEntity(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    DocumentVersionEntity entity = new DocumentVersionEntity();
                    entity.setTenantId(inv.getArgument(0));
                    entity.setKbId(inv.getArgument(1));
                    entity.setDocId(inv.getArgument(2));
                    entity.setVersion(inv.getArgument(3));
                    return entity;
                });
        service.self = service;
    }

    @Test
    void secondIngestSupersedesFirstVersion() {
        String contentV1 = "# 报销制度\nv1";
        String contentV2 = "# 报销制度\nv2";
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId("default");
        doc.setKbId("default");
        doc.setDocId("报销制度");
        doc.setDisplayName("报销制度");
        when(documentRepository.findByTenantIdAndKbIdAndDocId("default", "default", "报销制度"))
                .thenReturn(Optional.of(doc));
        when(versionOps.newVersionKey("default", "default", "报销制度")).thenReturn(V1, V2);
        when(documentVersionRepository.save(any(DocumentVersionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        stubPreviewGate("default", "default", "报销制度", V1, contentV1, List.of("v1"), "prv_v1");
        stubPreviewGate("default", "default", "报销制度", V2, contentV2, List.of("v2"), "prv_v2");

        var first = service.ingestText("default", "default",
                new IngestTextRequest(contentV1, null, "报销制度", null, null, null)).block();
        assertThat(first).isNotNull();
        assertThat(first.version()).isEqualTo(V1);
        assertThat(first.chunks()).isEqualTo(1);

        var second = service.ingestText("default", "default",
                new IngestTextRequest(contentV2, null, "报销制度", null, null, null)).block();
        assertThat(second).isNotNull();
        assertThat(second.version()).isEqualTo(V2);

        verify(versionOps, times(2)).supersedeActiveVersions("default", "default", "报销制度");
        verify(chunkIndexer, times(2)).embedAndIndex(
                eq("default"), eq("default"), eq("报销制度"), eq("报销制度"), anyString(), any());
    }

    @Test
    void publish_withoutPreviewId_fails() {
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId("default");
        doc.setKbId("default");
        doc.setDocId("doc-a");
        when(documentRepository.findByTenantIdAndKbIdAndDocId("default", "default", "doc-a"))
                .thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> service.publishVersion("default", "default", "doc-a", null).block())
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(RagErrorCode.PREVIEW_NOT_FOUND);
    }

    @Test
    void publish_whenContentChanged_returnsConflict() {
        String content = "# Title\nbody";
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId("default");
        doc.setKbId("default");
        doc.setDocId("doc-a");
        doc.setDisplayName("doc-a");
        doc.setSourceType("markdown");
        when(documentRepository.findByTenantIdAndKbIdAndDocId("default", "default", "doc-a"))
                .thenReturn(Optional.of(doc));
        DocumentVersionEntity draft = draftVersion(V1, content);
        when(versionOps.requireVersion(doc, V1)).thenReturn(draft);
        when(versionOps.readVersionContent(doc, draft)).thenReturn(content);
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of());
        ChunkPreviewRecord stale = new ChunkPreviewRecord(
                "prv_stale",
                "default",
                "default",
                "doc-a",
                V1,
                ChunkPreviewService.sha256Hex("old content"),
                ChunkStrategy.MARKDOWN,
                params,
                List.of(new ChunkDraft(0, "body", Map.of())),
                Instant.now().plusSeconds(600));
        when(chunkPreviewService.requirePreview("default", "default", "doc-a", "prv_stale"))
                .thenReturn(stale);

        assertThatThrownBy(() -> service.publishVersion("default", "default", "doc-a", "prv_stale").block())
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(RagErrorCode.PREVIEW_CONTENT_STALE);
        verify(chunkPreviewService, never()).consumePreview(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void publish_success_writesChunkMetadataAndIndexesPreviewChunks() {
        String content = "# Title\nbody";
        String previewId = "prv_ok";
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId("default");
        doc.setKbId("default");
        doc.setDocId("doc-a");
        doc.setDisplayName("doc-a");
        doc.setSourceType("markdown");
        when(documentRepository.findByTenantIdAndKbIdAndDocId("default", "default", "doc-a"))
                .thenReturn(Optional.of(doc));
        DocumentVersionEntity draft = draftVersion(V1, content);
        when(versionOps.requireVersion(doc, V1)).thenReturn(draft);
        when(versionOps.readVersionContent(doc, draft)).thenReturn(content);
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of("maxSize", 500));
        List<ChunkDraft> previewChunks = List.of(
                new ChunkDraft(0, "chunk-a", Map.of()),
                new ChunkDraft(1, "chunk-b", Map.of()));
        ChunkPreviewRecord preview = new ChunkPreviewRecord(
                previewId,
                "default",
                "default",
                "doc-a",
                V1,
                ChunkPreviewService.sha256Hex(content),
                ChunkStrategy.MARKDOWN,
                params,
                previewChunks,
                Instant.now().plusSeconds(600));
        when(chunkPreviewService.requirePreview("default", "default", "doc-a", previewId))
                .thenReturn(preview);
        when(chunkPreviewService.consumePreview("default", "default", "doc-a", previewId))
                .thenReturn(preview);
        when(documentVersionRepository.save(any(DocumentVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.save(any(DocumentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = service.publishVersion("default", "default", "doc-a", previewId).block();

        assertThat(result).isNotNull();
        assertThat(result.version()).isEqualTo(V1);
        assertThat(result.chunks()).isEqualTo(2);
        ArgumentCaptor<DocumentVersionEntity> versionCaptor = ArgumentCaptor.forClass(DocumentVersionEntity.class);
        verify(documentVersionRepository).save(versionCaptor.capture());
        DocumentVersionEntity saved = versionCaptor.getValue();
        assertThat(saved.getChunkStrategy()).isEqualTo("markdown");
        assertThat(saved.getChunkParamsJson()).contains("\"maxSize\":500");
        verify(chunkIndexer).embedAndIndex(
                eq("default"), eq("default"), eq("doc-a"), eq("doc-a"), eq(V1),
                eq(List.of("chunk-a", "chunk-b")));
        verify(chunkPreviewService).requirePreview("default", "default", "doc-a", previewId);
        verify(chunkPreviewService).consumePreview("default", "default", "doc-a", previewId);
    }

    @Test
    void chunkPreview_usesScrubbedDraftContent() {
        String content = "# Title\nbody";
        DocumentEntity doc = new DocumentEntity();
        doc.setTenantId("default");
        doc.setKbId("default");
        doc.setDocId("doc-a");
        doc.setSourceType("markdown");
        when(documentRepository.findByTenantIdAndKbIdAndDocId("default", "default", "doc-a"))
                .thenReturn(Optional.of(doc));
        DocumentVersionEntity draft = draftVersion(V1, content);
        when(versionOps.requireDraftVersion(doc, V1)).thenReturn(draft);
        when(versionOps.readVersionContent(doc, draft)).thenReturn(content);
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of());
        ChunkPreviewRecord record = new ChunkPreviewRecord(
                "prv_ok",
                "default",
                "default",
                "doc-a",
                V1,
                ChunkPreviewService.sha256Hex(content),
                ChunkStrategy.MARKDOWN,
                params,
                List.of(new ChunkDraft(0, "body", Map.of())),
                Instant.now().plusSeconds(600));
        when(chunkPreviewService.createPreview(
                "default", "default", "doc-a", V1, content, ChunkStrategy.MARKDOWN, params))
                .thenReturn(record);

        var response = service.chunkPreview(
                "default", "default", "doc-a", new ChunkPreviewRequest(V1, "markdown", Map.of()));

        assertThat(response.previewId()).isEqualTo("prv_ok");
        assertThat(response.chunkCount()).isEqualTo(1);
        assertThat(response.chunks().getFirst().text()).isEqualTo("body");
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
        when(versionOps.requireVersion(doc, V1)).thenReturn(version);

        service.supersedeVersion("default", "default", "doc-a", V1);

        verify(versionOps).markSuperseded(version);
    }

    @Test
    void documentVersionTimeFormat() {
        assertThat(DocumentVersionTime.fromInstant(java.time.Instant.parse("2026-07-01T03:00:11Z")))
                .matches("\\d{14}");
    }

    private void stubPreviewGate(
            String tenant,
            String kb,
            String docId,
            String version,
            String content,
            List<String> chunkTexts,
            String previewId) {
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of());
        List<ChunkDraft> drafts = chunkTexts.stream()
                .map(text -> new ChunkDraft(chunkTexts.indexOf(text), text, Map.of()))
                .toList();
        ChunkPreviewRecord created = new ChunkPreviewRecord(
                previewId,
                tenant,
                kb,
                docId,
                version,
                ChunkPreviewService.sha256Hex(content),
                ChunkStrategy.MARKDOWN,
                params,
                drafts,
                Instant.now().plusSeconds(600));
        when(chunkPreviewService.createPreview(tenant, kb, docId, version, content, ChunkStrategy.MARKDOWN, params))
                .thenReturn(created);
        when(chunkPreviewService.consumePreview(tenant, kb, docId, previewId)).thenReturn(created);
    }

    private static DocumentVersionEntity draftVersion(String version, String markdown) {
        DocumentVersionEntity entity = new DocumentVersionEntity();
        entity.setTenantId("default");
        entity.setKbId("default");
        entity.setDocId("doc-a");
        entity.setVersion(version);
        entity.setStatus("draft");
        entity.setParsedMarkdown(markdown);
        return entity;
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
