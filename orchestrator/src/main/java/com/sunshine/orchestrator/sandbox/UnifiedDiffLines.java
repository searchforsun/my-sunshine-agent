package com.sunshine.orchestrator.sandbox;

import com.sunshine.common.sandbox.SandboxEditDiffLine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 git unified diff 文本为结构化 diff 行（kind ∈ hunk|ctx|del|add），
 * 供右侧工作区「改动」diff 详情渲染。行号 1-based；hunk 头行号即该 hunk 起始行。
 */
public final class UnifiedDiffLines {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -([0-9]+)(?:,[0-9]+)? \\+([0-9]+)(?:,[0-9]+)? @@.*$");

    private UnifiedDiffLines() {
    }

    public static List<SandboxEditDiffLine> parse(String diffText) {
        if (diffText == null || diffText.isBlank()) {
            return List.of();
        }
        List<SandboxEditDiffLine> out = new ArrayList<>();
        int oldLine = 0;
        int newLine = 0;
        boolean inHunk = false;
        for (String raw : diffText.split("\n", -1)) {
            if (raw.startsWith("@@")) {
                int[] start = hunkStart(raw);
                oldLine = start[0];
                newLine = start[1];
                out.add(new SandboxEditDiffLine("hunk", raw, start[0], start[1]));
                inHunk = true;
            } else if (!inHunk || raw.isEmpty() || raw.startsWith("\\")) {
                // 跳过文件头（diff --git / index / --- / +++）与 "No newline at end of file" 标记
                continue;
            } else {
                char mark = raw.charAt(0);
                String text = raw.length() > 1 ? raw.substring(1) : "";
                switch (mark) {
                    case ' ' -> out.add(new SandboxEditDiffLine("ctx", text, oldLine++, newLine++));
                    case '-' -> out.add(new SandboxEditDiffLine("del", text, oldLine++, null));
                    case '+' -> out.add(new SandboxEditDiffLine("add", text, null, newLine++));
                    default -> { /* 忽略异常行 */ }
                }
            }
        }
        return out;
    }

    /** @@ -a,b +c,d @@ → [a, c]；未命中返回 [0, 0] */
    private static int[] hunkStart(String header) {
        Matcher m = HUNK_HEADER.matcher(header);
        if (!m.matches()) {
            return new int[]{0, 0};
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }
}
