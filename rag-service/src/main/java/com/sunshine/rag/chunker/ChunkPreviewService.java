package com.sunshine.rag.chunker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.exception.RagErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 分块预览 Token：Redis TTL 30 分钟，publish 消费后删除 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkPreviewService {

    static final Duration PREVIEW_TTL = Duration.ofMinutes(30);
    private static final String KEY_PREFIX = "rag:chunk-preview:";
    private static final String PREVIEW_ID_PREFIX = "prv_";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ChunkerRegistry chunkerRegistry;

    public ChunkPreviewRecord createPreview(
            String tenantId,
            String kbId,
            String docId,
            String version,
            String content,
            ChunkStrategy strategy,
            ChunkParams params) {
        String contentHash = sha256Hex(content);
        List<ChunkDraft> chunks = chunkerRegistry.chunk(strategy, content, params);
        String previewId = PREVIEW_ID_PREFIX + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(PREVIEW_TTL);
        ChunkPreviewRecord record = new ChunkPreviewRecord(
                previewId, tenantId, kbId, docId, version, contentHash, strategy, params, chunks, expiresAt);
        writeRecord(record);
        return record;
    }

    public ChunkPreviewRecord requirePreview(String tenantId, String kbId, String docId, String previewId) {
        return loadAndValidate(tenantId, kbId, docId, previewId);
    }

    public ChunkPreviewRecord consumePreview(String tenantId, String kbId, String docId, String previewId) {
        ChunkPreviewRecord record = loadAndValidate(tenantId, kbId, docId, previewId);
        redis.delete(key(previewId));
        return record;
    }

    private ChunkPreviewRecord loadAndValidate(String tenantId, String kbId, String docId, String previewId) {
        ChunkPreviewRecord record = readRecord(previewId);
        if (record == null) {
            throw new BizException(RagErrorCode.PREVIEW_NOT_FOUND);
        }
        if (!tenantId.equals(record.tenantId()) || !kbId.equals(record.kbId()) || !docId.equals(record.docId())) {
            throw new BizException(RagErrorCode.PREVIEW_MISMATCH);
        }
        if (record.expiresAt().isBefore(Instant.now())) {
            throw new BizException(RagErrorCode.PREVIEW_EXPIRED);
        }
        return record;
    }

    private void writeRecord(ChunkPreviewRecord record) {
        try {
            redis.opsForValue().set(key(record.previewId()), objectMapper.writeValueAsString(record), PREVIEW_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分块预览序列化失败", e);
        }
    }

    private ChunkPreviewRecord readRecord(String previewId) {
        if (previewId == null || previewId.isBlank()) {
            return null;
        }
        String json = redis.opsForValue().get(key(previewId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ChunkPreviewRecord.class);
        } catch (JsonProcessingException e) {
            log.warn("[ChunkPreview] 反序列化失败 previewId={}: {}", previewId, e.getMessage());
            return null;
        }
    }

    static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String key(String previewId) {
        return KEY_PREFIX + previewId.strip();
    }
}
