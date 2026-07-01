package com.sunshine.rag.pipeline;

import java.util.ArrayList;
import java.util.List;

/** 完整检索 pipeline trace */
public class RetrievalTrace {
    private final List<RetrievalStage> stages = new ArrayList<>();
    private int searchCount;

    public void addStage(RetrievalStage stage) {
        stages.add(stage);
    }

    public void incrementSearchCount() {
        searchCount++;
    }

    public List<RetrievalStage> stages() {
        return List.copyOf(stages);
    }

    public int searchCount() {
        return searchCount;
    }

    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("searchCount", searchCount);
        map.put("stages", stages.stream().map(RetrievalTrace::stageToMap).toList());
        return map;
    }

    private static java.util.Map<String, Object> stageToMap(RetrievalStage s) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", s.name());
        if (s.applied() != null) {
            m.put("applied", s.applied());
        }
        if (s.from() != null) {
            m.put("from", s.from());
        }
        if (s.to() != null) {
            m.put("to", s.to());
        }
        if (s.candidates() != null) {
            m.put("candidates", s.candidates());
        }
        m.put("latencyMs", s.latencyMs());
        if (s.scenarioLabel() != null && !s.scenarioLabel().isBlank()) {
            m.put("scenarioLabel", s.scenarioLabel());
        }
        return m;
    }
}
