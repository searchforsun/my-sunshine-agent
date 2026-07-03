package com.sunshine.rag.admin.eval.dto;

/** 发起评测任务 */
public record EvalRunRequest(
        String suiteKey,
        String kbId,
        String strategy,
        String configMode,
        Long configVersionId) {
}
