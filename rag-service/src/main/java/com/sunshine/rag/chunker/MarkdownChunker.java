package com.sunshine.rag.chunker;

import com.sunshine.rag.parser.MarkdownParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Markdown 语义分块：委托 MarkdownParser，按标题/表格/代码块等边界切分 */
@Component
@RequiredArgsConstructor
public class MarkdownChunker implements Chunker {

    private final MarkdownParser markdownParser;

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.MARKDOWN;
    }

    @Override
    public List<ChunkDraft> chunk(String markdown, ChunkParams params) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<String> texts = markdownParser.parse(markdown, params.maxSize());
        List<ChunkDraft> chunks = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            chunks.add(new ChunkDraft(i, texts.get(i), Map.of()));
        }
        return chunks;
    }
}
