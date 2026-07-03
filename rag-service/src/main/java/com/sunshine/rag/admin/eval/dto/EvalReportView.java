package com.sunshine.rag.admin.eval.dto;

import java.util.List;
import java.util.Map;

public record EvalReportView(
        Long reportId,
        Long jobId,
        Double recallAt5,
        Double mrr,
        Boolean passedGate,
        Double baselineRecallAt5,
        Map<String, Object> summary,
        List<Map<String, Object>> failedSamples,
        EvalSuggestResult suggestions,
        String reportMdPath,
        String reportJsonPath) {
}
