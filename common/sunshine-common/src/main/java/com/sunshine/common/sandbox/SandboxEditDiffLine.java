package com.sunshine.common.sandbox;

/** edit diff 单行：kind ∈ ctx|del|add|fold|hunk；行号 1-based，缺侧 null */
public record SandboxEditDiffLine(
        String kind,
        String text,
        Integer oldLine,
        Integer newLine) {
}
