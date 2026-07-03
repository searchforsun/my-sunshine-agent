package com.sunshine.rag.admin.config;

import java.util.Arrays;
import java.util.Optional;

/** tenant 级 Nacos publish scope（spec §6.3） */
public enum ConfigScope {
    RAG_SEARCH("rag-search", "检索参数", "sunshine-rag.yaml", "rag.search"),
    RAG_RERANK("rag-rerank", "Rerank 参数", "sunshine-rag.yaml", "rag.rerank"),
    RAG_CHUNK("rag-chunk", "分段参数", "sunshine-rag.yaml", "rag.chunk"),
    REWRITE_RAG("rewrite-rag", "RAG 改写", "sunshine-rag.yaml", "rag.rewrite.rag"),
    REWRITE_HYDE("rewrite-hyde", "HyDE", "sunshine-rag.yaml", "rag.rewrite.rag.hyde"),
    REWRITE_EMPTY_RECALL("rewrite-empty-recall", "零命中改写", "sunshine-rag.yaml", "rag.rewrite.empty-recall");

    private final String id;
    private final String label;
    private final String dataId;
    private final String nacosPath;

    ConfigScope(String id, String label, String dataId, String nacosPath) {
        this.id = id;
        this.label = label;
        this.dataId = dataId;
        this.nacosPath = nacosPath;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String dataId() {
        return dataId;
    }

    public String nacosPath() {
        return nacosPath;
    }

    public static ConfigScope require(String scope) {
        return from(scope).orElseThrow(() -> new IllegalArgumentException("未知 scope: " + scope));
    }

    public static Optional<ConfigScope> from(String scope) {
        if (scope == null || scope.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(item -> item.id.equals(scope)).findFirst();
    }
}
