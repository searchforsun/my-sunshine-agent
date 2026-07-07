package com.sunshine.orchestrator.rewrite;

import java.util.Optional;
import java.util.Set;

/**
 * Query 改写场景 id — 与 rag-service trace {@code stages[].name}、审计 {@code rewriteScenario} 对齐。
 * orchestrator 侧仅产生 {@link #INTENT} / {@link #PLANNER}；检索侧为 {@link #RAG} / {@link #HYDE} / {@link #EMPTY_RECALL}。
 */
public enum QueryRewriteScenario {

    INTENT("intent"),
    PLANNER("planner"),
    RAG("rag"),
    HYDE("hyde"),
    EMPTY_RECALL("empty-recall");

    private static final Set<String> RAG_RELATED_IDS = Set.of(RAG.id, HYDE.id, EMPTY_RECALL.id);

    private final String id;

    QueryRewriteScenario(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean matches(String scenario) {
        return id.equals(scenario);
    }

    public static Optional<QueryRewriteScenario> of(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            return Optional.empty();
        }
        for (QueryRewriteScenario value : values()) {
            if (value.id.equals(scenario)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static boolean isRagRelated(String scenario) {
        return scenario != null && RAG_RELATED_IDS.contains(scenario);
    }
}
