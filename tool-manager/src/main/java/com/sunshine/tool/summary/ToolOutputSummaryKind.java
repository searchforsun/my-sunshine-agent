package com.sunshine.tool.summary;

import java.util.Optional;

/** catalog {@code outputSummaryKind} — 工具输出一步摘要策略 */
public enum ToolOutputSummaryKind {

    HIT_COUNT("hit-count"),
    FINANCE_LIST("finance-list"),
    FINANCE_SUMMARY("finance-summary"),
    FINANCE_DETAIL("finance-detail"),
    OA_TASKS("oa-tasks"),
    TRUNCATE("truncate");

    private final String id;

    ToolOutputSummaryKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<ToolOutputSummaryKind> of(String kind) {
        if (kind == null || kind.isBlank()) {
            return Optional.empty();
        }
        for (ToolOutputSummaryKind value : values()) {
            if (value.id.equals(kind)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
