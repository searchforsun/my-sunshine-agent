package com.sunshine.rag.chunker;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.exception.RagErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 按 strategy 路由 Chunker，并强制执行 2000 块硬上限与连续 index */
@Component
@RequiredArgsConstructor
public class ChunkerRegistry {

    static final int CHUNK_HARD_LIMIT = 2000;

    private final List<Chunker> chunkers;

    public List<ChunkDraft> chunk(ChunkStrategy strategy, String markdown, ChunkParams params) {
        Chunker chunker = chunkers.stream()
                .filter(c -> c.strategy() == strategy)
                .findFirst()
                .orElseThrow(() -> new BizException(RagErrorCode.UNKNOWN_CHUNK_STRATEGY));
        List<ChunkDraft> drafts = chunker.chunk(markdown, params);
        if (drafts.size() > CHUNK_HARD_LIMIT) {
            throw new BizException(RagErrorCode.CHUNK_LIMIT_EXCEEDED);
        }
        return reindex(drafts);
    }

    private static List<ChunkDraft> reindex(List<ChunkDraft> drafts) {
        if (drafts.isEmpty()) {
            return drafts;
        }
        List<ChunkDraft> result = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft draft = drafts.get(i);
            result.add(new ChunkDraft(i, draft.text(), draft.meta()));
        }
        return result;
    }
}
