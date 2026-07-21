package com.sunshine.common.sandbox;

import java.util.List;

/** edit 变更 hunk：±contextRadius 上下文 + 绝对行号 */
public record SandboxEditDiff(
        String path,
        int contextRadius,
        List<SandboxEditDiffLine> lines) {

    public SandboxEditDiff withPath(String path) {
        return new SandboxEditDiff(path, contextRadius, lines);
    }

    /** 复制用 unified 文本（跳过 fold 行） */
    public String toUnifiedText() {
        StringBuilder sb = new StringBuilder();
        for (SandboxEditDiffLine l : lines) {
            if ("fold".equals(l.kind())) {
                continue;
            }
            char p = switch (l.kind()) {
                case "del" -> '-';
                case "add" -> '+';
                default -> ' ';
            };
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(p).append(l.text() != null ? l.text() : "");
        }
        return sb.toString();
    }
}
