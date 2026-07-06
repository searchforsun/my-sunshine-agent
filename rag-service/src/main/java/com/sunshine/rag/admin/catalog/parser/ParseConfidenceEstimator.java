package com.sunshine.rag.admin.catalog.parser;

import com.sunshine.rag.admin.catalog.DocumentSourceType;
import org.springframework.util.StringUtils;

/** 解析产物置信度（启发式，无 OCR 原生分数时的 L1 分流） */
public final class ParseConfidenceEstimator {

    private ParseConfidenceEstimator() {
    }

    public record Result(double confidence, boolean autoPass) {
    }

    public static Result estimate(String content, DocumentSourceType sourceType, double threshold) {
        if (!StringUtils.hasText(content)) {
            return new Result(0.0, false);
        }
        String text = content.strip();
        double score = 1.0;
        int len = text.length();
        if (len < 30) {
            score -= 0.45;
        } else if (len < 80) {
            score -= 0.2;
        }
        if (sourceType == DocumentSourceType.PDF) {
            long backslashes = text.chars().filter(ch -> ch == '\\').count();
            if (backslashes > 0 && backslashes * 1.0 / len > 0.04) {
                score -= 0.2;
            }
            if (text.contains("\\begin{") && !text.contains("|") && len < 300) {
                score -= 0.15;
            }
        }
        score = Math.max(0.0, Math.min(1.0, score));
        return new Result(score, score >= threshold);
    }
}
