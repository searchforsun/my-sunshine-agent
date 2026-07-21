package com.sunshine.rag.chunker;

import com.sunshine.rag.config.RagChunkProperties;
import com.sunshine.rag.parser.MarkdownParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    private static final int MAX_SIZE = 1200;

    private MarkdownParser parser;
    private MarkdownChunker chunker;

    @BeforeEach
    void setUp() {
        RagChunkProperties chunk = new RagChunkProperties();
        chunk.setMaxSize(MAX_SIZE);
        parser = new MarkdownParser(chunk);
        chunker = new MarkdownChunker(parser);
    }

    @Test
    void markdown_matchesParserOutputForSample() throws IOException {
        String markdown = readSample("leave-process-sample.md");
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of("maxSize", MAX_SIZE));
        List<String> expected = parser.parse(markdown, MAX_SIZE);
        List<ChunkDraft> chunks = chunker.chunk(markdown, params);
        assertThat(chunks).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(chunks.get(i).text()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void markdown_indicesAreSequentialFromZero() throws IOException {
        String markdown = readSample("leave-process-sample.md");
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of("maxSize", MAX_SIZE));
        List<ChunkDraft> chunks = chunker.chunk(markdown, params);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
    }

    @Test
    void markdown_metaIsEmpty() throws IOException {
        String markdown = readSample("leave-process-sample.md");
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of("maxSize", MAX_SIZE));
        List<ChunkDraft> chunks = chunker.chunk(markdown, params);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allMatch(chunk -> chunk.meta().isEmpty());
    }

    @Test
    void markdown_strategyIsMarkdown() {
        assertThat(chunker.strategy()).isEqualTo(ChunkStrategy.MARKDOWN);
    }

    @Test
    void markdown_nullOrBlank_returnsEmpty() {
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.MARKDOWN, Map.of("maxSize", MAX_SIZE));
        assertThat(chunker.chunk(null, params)).isEmpty();
        assertThat(chunker.chunk("   ", params)).isEmpty();
    }

    private static String readSample(String resourceName) throws IOException {
        try (var in = MarkdownChunkerTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
