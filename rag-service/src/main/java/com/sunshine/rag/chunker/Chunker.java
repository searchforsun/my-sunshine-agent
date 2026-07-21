package com.sunshine.rag.chunker;

import java.util.List;

/** 可插拔分块器：输入 Markdown 正文与策略参数，输出有序 ChunkDraft 列表 */
public interface Chunker {
    ChunkStrategy strategy();

    List<ChunkDraft> chunk(String markdown, ChunkParams params);
}
