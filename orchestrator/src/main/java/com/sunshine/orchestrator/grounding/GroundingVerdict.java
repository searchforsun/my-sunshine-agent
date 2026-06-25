package com.sunshine.orchestrator.grounding;

import java.util.List;

/** Grounding 校验结果 */
public record GroundingVerdict(
        boolean passed,
        String reason,
        List<String> triggers
) {
    public static GroundingVerdict pass() {
        return new GroundingVerdict(true, null, List.of());
    }

    public static GroundingVerdict fail(String reason, List<String> triggers) {
        return new GroundingVerdict(false, reason, triggers != null ? triggers : List.of());
    }
}
