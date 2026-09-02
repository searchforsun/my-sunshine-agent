package com.sunshine.rag.chunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.exception.RagErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChunkPreviewServiceTest {

    private static final String TENANT = "t1";
    private static final String KB = "kb1";
    private static final String DOC = "doc1";
    private static final String VERSION = "3";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ChunkerRegistry chunkerRegistry;

    private final Map<String, String> store = new HashMap<>();
    private ObjectMapper objectMapper;
    private ChunkPreviewService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ChunkPreviewService(redis, objectMapper, chunkerRegistry);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        lenient().doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void createRequireConsume_happyPath() throws Exception {
        String content = "# Title\n\nbody";
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 800, "overlap", 100));
        List<ChunkDraft> chunks = List.of(new ChunkDraft(0, "body", Map.of()));
        when(chunkerRegistry.chunk(ChunkStrategy.FIXED, content, params)).thenReturn(chunks);

        ChunkPreviewRecord created = service.createPreview(
                TENANT, KB, DOC, VERSION, content, ChunkStrategy.FIXED, params);

        assertThat(created.previewId()).startsWith("prv_");
        assertThat(created.contentHash()).isEqualTo(ChunkPreviewService.sha256Hex(content));
        assertThat(created.chunks()).isEqualTo(chunks);
        assertThat(created.expiresAt()).isAfter(Instant.now());

        ChunkPreviewRecord required = service.requirePreview(TENANT, KB, DOC, created.previewId());
        assertThat(required).isEqualTo(created);
        assertThat(store).containsKey("rag:chunk-preview:" + created.previewId());

        ChunkPreviewRecord consumed = service.consumePreview(TENANT, KB, DOC, created.previewId());
        assertThat(consumed).isEqualTo(created);
        verify(redis).delete("rag:chunk-preview:" + created.previewId());
    }

    @Test
    void requirePreview_missingKey_throwsNotFound() {
        assertThatThrownBy(() -> service.requirePreview(TENANT, KB, DOC, "prv_missing"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(RagErrorCode.PREVIEW_NOT_FOUND);
    }

    @Test
    void requirePreview_expired_throwsExpired() throws Exception {
        ChunkPreviewRecord expired = sampleRecord("prv_expired", Instant.now().minusSeconds(60));
        store.put("rag:chunk-preview:" + expired.previewId(), objectMapper.writeValueAsString(expired));

        assertThatThrownBy(() -> service.requirePreview(TENANT, KB, DOC, expired.previewId()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(RagErrorCode.PREVIEW_EXPIRED);
    }

    @Test
    void requirePreview_wrongDocId_throwsMismatch() throws Exception {
        ChunkPreviewRecord record = sampleRecord("prv_mismatch", Instant.now().plusSeconds(600));
        store.put("rag:chunk-preview:" + record.previewId(), objectMapper.writeValueAsString(record));

        assertThatThrownBy(() -> service.requirePreview(TENANT, KB, "other-doc", record.previewId()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(RagErrorCode.PREVIEW_MISMATCH);
    }

    @Test
    void createPreview_setsRedisTtl() {
        String content = "hello";
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of());
        when(chunkerRegistry.chunk(ChunkStrategy.MARKDOWN, content, params))
                .thenReturn(List.of(new ChunkDraft(0, content, Map.of())));

        ChunkPreviewRecord created = service.createPreview(
                TENANT, KB, DOC, VERSION, content, ChunkStrategy.MARKDOWN, params);

        verify(valueOps).set(
                eq("rag:chunk-preview:" + created.previewId()),
                anyString(),
                eq(ChunkPreviewService.PREVIEW_TTL));
    }

    private ChunkPreviewRecord sampleRecord(String previewId, Instant expiresAt) {
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 800, "overlap", 100));
        return new ChunkPreviewRecord(
                previewId,
                TENANT,
                KB,
                DOC,
                VERSION,
                "sha256:abc",
                ChunkStrategy.FIXED,
                params,
                List.of(new ChunkDraft(0, "x", Map.of())),
                expiresAt);
    }
}
