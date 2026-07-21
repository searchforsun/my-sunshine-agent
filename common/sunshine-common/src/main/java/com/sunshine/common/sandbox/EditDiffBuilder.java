package com.sunshine.common.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于改前全文定位 old_string，生成带 ±contextRadius 上下文与绝对行号的 edit diff。
 * 行级 LCS 语义同 {@code HitlParamSupport.formatEditUnifiedDiff}。
 */
public final class EditDiffBuilder {

    private EditDiffBuilder() {
    }

    public static SandboxEditDiff build(String before, String oldString, String newString, int contextRadius) {
        return tryBuild(before, oldString, newString, contextRadius)
                .orElseThrow(() -> new IllegalArgumentException(
                        "old_string not found or not unique in before content"));
    }

    public static Optional<SandboxEditDiff> tryBuild(
            String before, String oldString, String newString, int contextRadius) {
        if (before == null || oldString == null || newString == null) {
            return Optional.empty();
        }
        int count = countOccurrences(before, oldString);
        if (count != 1) {
            return Optional.empty();
        }
        String[] beforeLines = splitLines(before);
        int startLine = lineIndexAt(before, before.indexOf(oldString));
        String[] oldLines = splitLines(oldString);
        int endLine = startLine + oldLines.length;
        String[] newLines = splitLines(newString);
        List<FragmentLine> fragment = lcsFragment(oldLines, newLines);
        int contentLines = contentLineCount(beforeLines);
        int ctxStart = Math.max(0, startLine - contextRadius);
        int ctxEnd = Math.min(contentLines, endLine + contextRadius);
        List<SandboxEditDiffLine> out = new ArrayList<>();
        if (ctxStart > 0) {
            out.add(foldLine());
        }
        int oldLine = ctxStart + 1;
        int newLine = ctxStart + 1;
        for (int i = ctxStart; i < startLine; i++) {
            out.add(ctxLine(beforeLines[i], oldLine, newLine));
            oldLine++;
            newLine++;
        }
        for (FragmentLine fl : fragment) {
            switch (fl.kind()) {
                case "ctx" -> {
                    out.add(ctxLine(fl.text(), oldLine, newLine));
                    oldLine++;
                    newLine++;
                }
                case "del" -> {
                    out.add(new SandboxEditDiffLine("del", fl.text(), oldLine, null));
                    oldLine++;
                }
                case "add" -> {
                    out.add(new SandboxEditDiffLine("add", fl.text(), null, newLine));
                    newLine++;
                }
                default -> throw new IllegalStateException("unexpected kind: " + fl.kind());
            }
        }
        for (int i = endLine; i < ctxEnd; i++) {
            out.add(ctxLine(beforeLines[i], oldLine, newLine));
            oldLine++;
            newLine++;
        }
        if (contentLines > ctxEnd) {
            out.add(foldLine());
        }
        return Optional.of(new SandboxEditDiff(null, contextRadius, List.copyOf(out)));
    }

    /** 末行仅因尾换行为空时不计入上下文窗口，但仍触发尾 fold */
    private static int contentLineCount(String[] lines) {
        int n = lines.length;
        if (n > 0 && lines[n - 1].isEmpty()) {
            return n - 1;
        }
        return n;
    }

    private static SandboxEditDiffLine foldLine() {
        return new SandboxEditDiffLine("fold", "", null, null);
    }

    private static SandboxEditDiffLine ctxLine(String text, int oldLine, int newLine) {
        return new SandboxEditDiffLine("ctx", text, oldLine, newLine);
    }

    private static int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + needle.length();
        }
        return count;
    }

    private static int lineIndexAt(String text, int charIndex) {
        int line = 0;
        for (int i = 0; i < charIndex && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String[] splitLines(String text) {
        if (text.isEmpty()) {
            return new String[]{""};
        }
        return text.split("\n", -1);
    }

    private record FragmentLine(String kind, String text) {
    }

    private static List<FragmentLine> lcsFragment(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a[i].equals(b[j])) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        List<FragmentLine> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                out.add(new FragmentLine("ctx", a[i]));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(new FragmentLine("del", a[i++]));
            } else {
                out.add(new FragmentLine("add", b[j++]));
            }
        }
        while (i < n) {
            out.add(new FragmentLine("del", a[i++]));
        }
        while (j < m) {
            out.add(new FragmentLine("add", b[j++]));
        }
        return out;
    }
}
