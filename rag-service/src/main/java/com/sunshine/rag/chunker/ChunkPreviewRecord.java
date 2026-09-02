package com.sunshine.rag.chunker;

import java.time.Instant;
import java.util.List;

/** Redis 分块预览快照：publish 消费前校验归属与 contentHash */
public record ChunkPreviewRecord(
        String previewId,
        String tenantId,
        String kbId,
        String docId,
        String version,
        String contentHash,
        ChunkStrategy strategy,
        ChunkParams params,
        List<ChunkDraft> chunks,
        Instant expiresAt) {
}
