package com.sunshine.rag.chunker;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.exception.RagErrorCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkerRegistryTest {

    @Test
    void unknownStrategy_throwsBizException() {
        ChunkerRegistry registry = new ChunkerRegistry(List.of());
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 800, "overlap", 100));
        assertThatThrownBy(() -> registry.chunk(ChunkStrategy.FIXED, "text", params))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(RagErrorCode.UNKNOWN_CHUNK_STRATEGY);
    }

    @Test
    void missingBeanForStrategy_throwsUnknownStrategy() {
        Chunker markdownOnly = new Chunker() {
            @Override
            public ChunkStrategy strategy() {
                return ChunkStrategy.MARKDOWN;
            }

            @Override
            public List<ChunkDraft> chunk(String markdown, ChunkParams params) {
                return List.of(new ChunkDraft(0, markdown, Map.of()));
            }
        };
        ChunkerRegistry registry = new ChunkerRegistry(List.of(markdownOnly));
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 800, "overlap", 100));
        assertThatThrownBy(() -> registry.chunk(ChunkStrategy.FIXED, "text", params))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(RagErrorCode.UNKNOWN_CHUNK_STRATEGY);
    }

    @Test
    void chunkLimitExceeded_throwsBizException() {
        Chunker overLimit = new Chunker() {
            @Override
            public ChunkStrategy strategy() {
                return ChunkStrategy.FIXED;
            }

            @Override
            public List<ChunkDraft> chunk(String markdown, ChunkParams params) {
                List<ChunkDraft> drafts = new ArrayList<>(ChunkerRegistry.CHUNK_HARD_LIMIT + 1);
                for (int i = 0; i <= ChunkerRegistry.CHUNK_HARD_LIMIT; i++) {
                    drafts.add(new ChunkDraft(i, "x", Map.of()));
                }
                return drafts;
            }
        };
        ChunkerRegistry registry = new ChunkerRegistry(List.of(overLimit));
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 800, "overlap", 100));
        assertThatThrownBy(() -> registry.chunk(ChunkStrategy.FIXED, "ignored", params))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(RagErrorCode.CHUNK_LIMIT_EXCEEDED);
    }

    @Test
    void chunk_rewritesIndicesToZeroBasedSequential() {
        Chunker skewedIndices = new Chunker() {
            @Override
            public ChunkStrategy strategy() {
                return ChunkStrategy.FIXED;
            }

            @Override
            public List<ChunkDraft> chunk(String markdown, ChunkParams params) {
                return List.of(
                        new ChunkDraft(10, "a", Map.of()),
                        new ChunkDraft(99, "b", Map.of("k", "v")));
            }
        };
        ChunkerRegistry registry = new ChunkerRegistry(List.of(skewedIndices));
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 800, "overlap", 100));
        List<ChunkDraft> drafts = registry.chunk(ChunkStrategy.FIXED, "ab", params);
        assertThat(drafts).hasSize(2);
        assertThat(drafts.get(0).index()).isZero();
        assertThat(drafts.get(1).index()).isEqualTo(1);
        assertThat(drafts.get(0).text()).isEqualTo("a");
        assertThat(drafts.get(1).meta()).containsEntry("k", "v");
    }

    @Test
    void chunk_delegatesToRegisteredChunker() {
        ChunkerRegistry registry = new ChunkerRegistry(List.of(new FixedLengthChunker()));
        String text = "x".repeat(100);
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 40, "overlap", 10));
        List<ChunkDraft> drafts = registry.chunk(ChunkStrategy.FIXED, text, params);
        assertThat(drafts).isNotEmpty();
        for (int i = 0; i < drafts.size(); i++) {
            assertThat(drafts.get(i).index()).isEqualTo(i);
        }
    }
}
