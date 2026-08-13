package com.sunshine.workflow.dto;

import java.util.List;

/** Runtime catalog 条目 — 供 orchestrator L3 / Chat # 补全 */
public record WorkflowCatalogEntry(
        String id,
        String mode,
        String displayName,
        String description,
        String kind,
        List<String> examples,
        List<String> nodes,
        String intentAfter) {
}
