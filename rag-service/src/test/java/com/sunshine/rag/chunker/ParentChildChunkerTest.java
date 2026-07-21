package com.sunshine.rag.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChildChunkerTest {

    private final ParentChildChunker chunker = new ParentChildChunker();

    @Test
    void parentChild_emitsParentsAndChildrenWithLinks() {
        String text = "字".repeat(2500);
        List<ChunkDraft> drafts = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.PARENT_CHILD,
                        Map.of("parentSize", 1000, "childSize", 200, "childOverlap", 20)));
        List<ChunkDraft> parents = drafts.stream()
                .filter(d -> "parent".equals(d.meta().get("level"))).toList();
        List<ChunkDraft> children = drafts.stream()
                .filter(d -> "child".equals(d.meta().get("level"))).toList();
        assertThat(parents).isNotEmpty();
        assertThat(children).isNotEmpty();
        assertThat(children.get(0).meta().get("parentIndex")).isInstanceOf(Integer.class);
    }

    @Test
    void parentChild_parentsFirstThenChildrenWithGlobalIndices() {
        String text = "字".repeat(2500);
        List<ChunkDraft> drafts = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.PARENT_CHILD,
                        Map.of("parentSize", 1000, "childSize", 200, "childOverlap", 20)));
        int parentCount = (int) drafts.stream()
                .filter(d -> "parent".equals(d.meta().get("level"))).count();
        for (int i = 0; i < drafts.size(); i++) {
            assertThat(drafts.get(i).index()).isEqualTo(i);
        }
        for (int i = 0; i < parentCount; i++) {
            assertThat(drafts.get(i).meta().get("level")).isEqualTo("parent");
        }
        for (int i = parentCount; i < drafts.size(); i++) {
            ChunkDraft child = drafts.get(i);
            assertThat(child.meta().get("level")).isEqualTo("child");
            int parentIndex = (Integer) child.meta().get("parentIndex");
            assertThat(parentIndex).isBetween(0, parentCount - 1);
        }
    }

    @Test
    void parentChild_nullOrBlank_returnsEmpty() {
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.PARENT_CHILD,
                Map.of("parentSize", 1000, "childSize", 200, "childOverlap", 20));
        assertThat(chunker.chunk(null, params)).isEmpty();
        assertThat(chunker.chunk("   ", params)).isEmpty();
    }
}
