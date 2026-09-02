package com.sunshine.rag.chunker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 父子分块：先定长切父块，再在各父块内滑动窗口切子块；返回顺序为全部 parent 再全部 child */
@Component
public class ParentChildChunker implements Chunker {

    private final FixedLengthChunker fixedLengthChunker;

    public ParentChildChunker() {
        this(new FixedLengthChunker());
    }

    ParentChildChunker(FixedLengthChunker fixedLengthChunker) {
        this.fixedLengthChunker = fixedLengthChunker;
    }

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.PARENT_CHILD;
    }

    @Override
    public List<ChunkDraft> chunk(String markdown, ChunkParams params) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        int parentSize = params.parentSize();
        int childSize = params.childSize();
        int childOverlap = params.childOverlap();
        List<String> parentTexts = splitParents(markdown, parentSize);
        List<ChunkDraft> parents = new ArrayList<>(parentTexts.size());
        for (int i = 0; i < parentTexts.size(); i++) {
            parents.add(new ChunkDraft(i, parentTexts.get(i), Map.of("level", "parent")));
        }
        List<ChunkDraft> children = new ArrayList<>();
        int childIndex = parentTexts.size();
        ChunkParams childParams = ChunkParams.forStrategy(ChunkStrategy.FIXED,
                Map.of("maxSize", childSize, "overlap", childOverlap));
        for (int parentIdx = 0; parentIdx < parentTexts.size(); parentIdx++) {
            List<ChunkDraft> childChunks = fixedLengthChunker.chunk(parentTexts.get(parentIdx), childParams);
            for (ChunkDraft childChunk : childChunks) {
                children.add(new ChunkDraft(childIndex++, childChunk.text(),
                        Map.of("level", "child", "parentIndex", parentIdx)));
            }
        }
        List<ChunkDraft> result = new ArrayList<>(parents.size() + children.size());
        result.addAll(parents);
        result.addAll(children);
        return result;
    }

    /** 定长硬切父块，无 overlap */
    static List<String> splitParents(String text, int parentSize) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int len = text.length();
        while (start < len) {
            int end = Math.min(start + parentSize, len);
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }
}
