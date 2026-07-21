package com.sunshine.rag.chunker;

import java.util.LinkedHashMap;
import java.util.Map;

/** 不可变参数袋：按 strategy 校验并填默认值 */
public record ChunkParams(
        int maxSize,
        int overlap,
        double similarityThreshold,
        int minChunkSize,
        int parentSize,
        int childSize,
        int childOverlap) {

    private static final int DEFAULT_MARKDOWN_MAX_SIZE = 1200;
    private static final int DEFAULT_FIXED_MAX_SIZE = 800;
    private static final int DEFAULT_FIXED_OVERLAP = 100;
    private static final int DEFAULT_RECURSIVE_MAX_SIZE = 1000;
    private static final int DEFAULT_RECURSIVE_OVERLAP = 80;
    private static final int DEFAULT_SEMANTIC_MAX_SIZE = 1200;
    private static final double DEFAULT_SEMANTIC_SIMILARITY_THRESHOLD = 0.55;
    private static final int DEFAULT_SEMANTIC_MIN_CHUNK_SIZE = 200;
    private static final int DEFAULT_PARENT_SIZE = 2000;
    private static final int DEFAULT_CHILD_SIZE = 400;
    private static final int DEFAULT_CHILD_OVERLAP = 50;

    public static ChunkParams forStrategy(ChunkStrategy strategy, Map<String, Object> raw) {
        Map<String, Object> source = raw == null ? Map.of() : raw;
        return switch (strategy) {
            case MARKDOWN -> markdown(source);
            case FIXED -> fixed(source);
            case RECURSIVE -> recursive(source);
            case SEMANTIC -> semantic(source);
            case PARENT_CHILD -> parentChild(source);
        };
    }

    /** 仅序列化该策略相关键（由字段非零模式推断策略域） */
    public Map<String, Object> asMap() {
        if (parentSize > 0 || childSize > 0) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("parentSize", parentSize);
            out.put("childSize", childSize);
            out.put("childOverlap", childOverlap);
            return out;
        }
        if (similarityThreshold > 0 || minChunkSize > 0) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("maxSize", maxSize);
            out.put("similarityThreshold", similarityThreshold);
            out.put("minChunkSize", minChunkSize);
            return out;
        }
        if (overlap > 0) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("maxSize", maxSize);
            out.put("overlap", overlap);
            return out;
        }
        if (maxSize > 0) {
            return Map.of("maxSize", maxSize);
        }
        return Map.of();
    }

    private static ChunkParams markdown(Map<String, Object> raw) {
        int maxSize = requirePositiveInt(raw, "maxSize", DEFAULT_MARKDOWN_MAX_SIZE);
        return new ChunkParams(maxSize, 0, 0, 0, 0, 0, 0);
    }

    private static ChunkParams fixed(Map<String, Object> raw) {
        int maxSize = requirePositiveInt(raw, "maxSize", DEFAULT_FIXED_MAX_SIZE);
        int overlap = requireNonNegativeInt(raw, "overlap", DEFAULT_FIXED_OVERLAP);
        return new ChunkParams(maxSize, overlap, 0, 0, 0, 0, 0);
    }

    private static ChunkParams recursive(Map<String, Object> raw) {
        int maxSize = requirePositiveInt(raw, "maxSize", DEFAULT_RECURSIVE_MAX_SIZE);
        int overlap = requireNonNegativeInt(raw, "overlap", DEFAULT_RECURSIVE_OVERLAP);
        return new ChunkParams(maxSize, overlap, 0, 0, 0, 0, 0);
    }

    private static ChunkParams semantic(Map<String, Object> raw) {
        int maxSize = requirePositiveInt(raw, "maxSize", DEFAULT_SEMANTIC_MAX_SIZE);
        double similarityThreshold = requirePositiveDouble(raw, "similarityThreshold",
                DEFAULT_SEMANTIC_SIMILARITY_THRESHOLD);
        int minChunkSize = requirePositiveInt(raw, "minChunkSize", DEFAULT_SEMANTIC_MIN_CHUNK_SIZE);
        return new ChunkParams(maxSize, 0, similarityThreshold, minChunkSize, 0, 0, 0);
    }

    private static ChunkParams parentChild(Map<String, Object> raw) {
        int parentSize = requirePositiveInt(raw, "parentSize", DEFAULT_PARENT_SIZE);
        int childSize = requirePositiveInt(raw, "childSize", DEFAULT_CHILD_SIZE);
        int childOverlap = requireNonNegativeInt(raw, "childOverlap", DEFAULT_CHILD_OVERLAP);
        return new ChunkParams(0, 0, 0, 0, parentSize, childSize, childOverlap);
    }

    private static int requirePositiveInt(Map<String, Object> raw, String key, int defaultValue) {
        int value = parseInt(raw.get(key), defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static int requireNonNegativeInt(Map<String, Object> raw, String key, int defaultValue) {
        int value = parseInt(raw.get(key), defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(key + " must be non-negative");
        }
        return value;
    }

    private static double requirePositiveDouble(Map<String, Object> raw, String key, double defaultValue) {
        double value = parseDouble(raw.get(key), defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }
        return value;
    }

    private static int parseInt(Object raw, int defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text.trim());
        }
        throw new IllegalArgumentException("Invalid integer value: " + raw);
    }

    private static double parseDouble(Object raw, double defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            return Double.parseDouble(text.trim());
        }
        throw new IllegalArgumentException("Invalid double value: " + raw);
    }
}
