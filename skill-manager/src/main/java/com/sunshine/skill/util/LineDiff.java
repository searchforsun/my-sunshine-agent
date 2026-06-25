package com.sunshine.skill.util;

import com.sunshine.skill.dto.SkillDiffLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 行级 diff — 基于 LCS，适用于 SKILL.md 规模文本 */
public final class LineDiff {

    private LineDiff() {
    }

    public static List<SkillDiffLine> diff(String left, String right) {
        List<String> a = splitLines(left);
        List<String> b = splitLines(right);
        int[][] lcs = buildLcs(a, b);
        List<SkillDiffLine> lines = new ArrayList<>();
        int i = a.size();
        int j = b.size();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
                lines.add(0, new SkillDiffLine("unchanged", a.get(i - 1), i, j));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                lines.add(0, new SkillDiffLine("added", b.get(j - 1), null, j));
                j--;
            } else {
                lines.add(0, new SkillDiffLine("removed", a.get(i - 1), i, null));
                i--;
            }
        }
        return lines;
    }

    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(text.split("\n", -1));
    }

    private static int[][] buildLcs(List<String> a, List<String> b) {
        int[][] dp = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }
}
