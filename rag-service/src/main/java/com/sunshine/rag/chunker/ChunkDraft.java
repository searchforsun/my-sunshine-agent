package com.sunshine.rag.chunker;

import java.util.Map;

/** 分块草稿：index 从 0 递增；parent_child 时 meta 含 level / parentIndex */
public record ChunkDraft(
        int index,
        String text,
        Map<String, Object> meta) {
    public int charCount() {
        return text == null ? 0 : text.length();
    }
}
