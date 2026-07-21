package com.sunshine.rag.chunker;

import com.sunshine.rag.service.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticChunkerTest {

    private static final List<Float> TOPIC_A = List.of(1.0f, 0.0f, 0.0f);
    private static final List<Float> TOPIC_B = List.of(0.0f, 1.0f, 0.0f);

    @Mock
    private EmbeddingService embeddingService;

    private SemanticChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new SemanticChunker(embeddingService);
    }

    @Test
    void semantic_splitsAtLowSimilarity() {
        when(embeddingService.embed(anyString())).thenAnswer(inv -> {
            String sentence = inv.getArgument(0);
            if (sentence.contains("甲") || sentence.contains("乙")) {
                return Mono.just(TOPIC_A);
            }
            return Mono.just(TOPIC_B);
        });
        List<ChunkDraft> out = chunker.chunk("句子甲。句子乙。完全无关的丙。",
                ChunkParams.forStrategy(ChunkStrategy.SEMANTIC,
                        Map.of("maxSize", 1200, "similarityThreshold", 0.5, "minChunkSize", 1)));
        assertThat(out).hasSizeGreaterThanOrEqualTo(2);
        assertThat(out.get(0).text()).contains("甲").contains("乙");
        assertThat(out.get(1).text()).contains("丙");
    }

    @Test
    void semantic_nullOrBlank_returnsEmpty() {
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.SEMANTIC,
                Map.of("maxSize", 1200, "similarityThreshold", 0.5, "minChunkSize", 1));
        assertThat(chunker.chunk(null, params)).isEmpty();
        assertThat(chunker.chunk("   ", params)).isEmpty();
    }

    @Test
    void semantic_respectsMinChunkSizeBeforeSplit() {
        when(embeddingService.embed(anyString())).thenReturn(Mono.just(TOPIC_A), Mono.just(TOPIC_B));
        List<ChunkDraft> out = chunker.chunk("短A。短B。",
                ChunkParams.forStrategy(ChunkStrategy.SEMANTIC,
                        Map.of("maxSize", 1200, "similarityThreshold", 0.5, "minChunkSize", 100)));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).text()).contains("短A").contains("短B");
    }

    @Test
    void semantic_secondarySplitWhenSegmentExceedsMaxSize() {
        when(embeddingService.embed(anyString())).thenReturn(Mono.just(TOPIC_A));
        String longBody = "x".repeat(80);
        String text = longBody + "。" + longBody + "。";
        List<ChunkDraft> out = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.SEMANTIC,
                        Map.of("maxSize", 50, "similarityThreshold", 0.99, "minChunkSize", 1)));
        assertThat(out.size()).isGreaterThan(1);
        assertThat(out).allSatisfy(d -> assertThat(d.text().length()).isLessThanOrEqualTo(50));
    }

    @Test
    void semantic_strategyIsSemantic() {
        assertThat(chunker.strategy()).isEqualTo(ChunkStrategy.SEMANTIC);
    }
}
