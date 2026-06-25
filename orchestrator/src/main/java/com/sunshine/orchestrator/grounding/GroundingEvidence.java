package com.sunshine.orchestrator.grounding;

import java.util.List;

/** Grounding 证据 — tool/rag/上游上下文是否支撑企业数据表述 */
public record GroundingEvidence(
        boolean hasToolOrRag,
        List<String> sourceTexts
) {
    public static GroundingEvidence none() {
        return new GroundingEvidence(false, List.of());
    }

    public static GroundingEvidence supported(List<String> sourceTexts) {
        List<String> texts = sourceTexts != null ? sourceTexts : List.of();
        return new GroundingEvidence(true, texts);
    }
}
