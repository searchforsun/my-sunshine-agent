package com.sunshine.rag.chunker;

import com.sunshine.rag.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 语义边界分块：相邻句 embedding 相似度低谷切段，超长段再定长二次切 */
@Component
@RequiredArgsConstructor
public class SemanticChunker implements Chunker {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？.!?\\n])");

    private final EmbeddingService embeddingService;

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.SEMANTIC;
    }

    @Override
    public List<ChunkDraft> chunk(String markdown, ChunkParams params) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<String> sentences = splitSentences(markdown);
        if (sentences.isEmpty()) {
            return List.of();
        }
        List<List<Float>> embeddings = new ArrayList<>(sentences.size());
        for (String sentence : sentences) {
            embeddings.add(embeddingService.embed(sentence).block());
        }
        List<String> segments = packBySimilarity(sentences, embeddings,
                params.similarityThreshold(), params.minChunkSize());
        List<ChunkDraft> chunks = new ArrayList<>();
        for (String segment : segments) {
            for (String part : enforceMaxSize(segment, params.maxSize())) {
                chunks.add(new ChunkDraft(chunks.size(), part, Map.of()));
            }
        }
        return chunks;
    }

    static List<String> splitSentences(String markdown) {
        List<String> sentences = new ArrayList<>();
        for (String part : SENTENCE_SPLIT.split(markdown)) {
            if (part != null && !part.isBlank()) {
                sentences.add(part);
            }
        }
        return sentences;
    }

    private static List<String> packBySimilarity(List<String> sentences, List<List<Float>> embeddings,
            double similarityThreshold, int minChunkSize) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder(sentences.get(0));
        for (int i = 0; i < sentences.size() - 1; i++) {
            double sim = cosineSimilarity(embeddings.get(i), embeddings.get(i + 1));
            if (sim < similarityThreshold && current.length() >= minChunkSize) {
                segments.add(current.toString());
                current = new StringBuilder(sentences.get(i + 1));
            } else {
                current.append(sentences.get(i + 1));
            }
        }
        segments.add(current.toString());
        return segments;
    }

    private static List<String> enforceMaxSize(String segment, int maxSize) {
        if (segment.length() <= maxSize) {
            return List.of(segment);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        int len = segment.length();
        while (start < len) {
            int cutEnd = FixedLengthChunker.findCutEnd(segment, start, maxSize);
            parts.add(segment.substring(start, cutEnd));
            if (cutEnd >= len) {
                break;
            }
            start = cutEnd;
        }
        return parts;
    }

    static double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a.size() != b.size()) {
            throw new IllegalArgumentException("Vector dimension mismatch");
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.size(); i++) {
            float av = a.get(i);
            float bv = b.get(i);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
