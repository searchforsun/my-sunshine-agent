package com.sunshine.rag.pipeline;

import java.util.List;
import java.util.Map;

/** Pipeline 单阶段 trace（改写或检索中间态） */
public record RetrievalStage(
        String name,
        Boolean applied,
        String from,
        String to,
        List<Map<String, Object>> candidates,
        long latencyMs,
        String scenarioLabel) {
}
