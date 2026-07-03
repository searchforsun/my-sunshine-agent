package com.sunshine.rag.admin.eval;

import com.sunshine.rag.admin.eval.dto.ConfigSuggestionItem;
import com.sunshine.rag.admin.eval.dto.EvalSuggestResult;
import com.sunshine.rag.admin.eval.dto.TextSuggestionItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 过滤与门禁失败模式矛盾或无效的参数建议 */
public final class EvalSuggestValidator {

    private static final Set<String> THRESHOLD_PATHS = Set.of(
            "search.minScore",
            "rerank.minScore",
            "rerank.minRelevance");

    private EvalSuggestValidator() {
    }

    public static EvalSuggestResult validate(EvalSuggestResult raw, List<String> failureModes) {
        boolean negativeEmptyFail = failureModes.contains(EvalSuggestContextBuilder.FailureMode.NEGATIVE_EMPTY_RATE_LOW.name());
        boolean positiveEmptyFail = failureModes.contains(EvalSuggestContextBuilder.FailureMode.POSITIVE_EMPTY_RATE_HIGH.name());
        List<ConfigSuggestionItem> kept = new ArrayList<>();
        List<String> droppedNotes = new ArrayList<>();
        for (ConfigSuggestionItem item : raw.suggestions()) {
            String reject = rejectReason(item, negativeEmptyFail, positiveEmptyFail);
            if (reject != null) {
                droppedNotes.add(item.path() + "：" + reject);
            } else {
                kept.add(item);
            }
        }
        String diagnosis = raw.diagnosis();
        if (!droppedNotes.isEmpty()) {
            diagnosis = appendNote(diagnosis, "以下参数建议与门禁失败模式冲突或暂不可生效，已自动过滤："
                    + String.join("；", droppedNotes));
        }
        List<TextSuggestionItem> textItems = raw.textSuggestions();
        if (negativeEmptyFail && !textItems.isEmpty()) {
            diagnosis = appendNote(diagnosis,
                    "当前主因是负例误召回：rewrite Prompt 改写可能扩大域内词命中面，应用前请确认不会进一步降低负例 EmptyRate");
        }
        return new EvalSuggestResult(diagnosis, kept, textItems);
    }

    private static String appendNote(String diagnosis, String note) {
        if (diagnosis == null || diagnosis.isBlank()) {
            return note;
        }
        return diagnosis + "\n\n" + note;
    }

    static String rejectReason(ConfigSuggestionItem item, boolean negativeEmptyFail, boolean positiveEmptyFail) {
        String path = item.path();
        if ("rerank.minRelevance".equals(path)) {
            return "bundle 内 minRelevance 尚未接入检索运行时，请改 search.minScore 或 rerank.minScore";
        }
        if (!THRESHOLD_PATHS.contains(path)) {
            return null;
        }
        Double current = toDouble(item.current());
        Double proposed = toDouble(item.proposed());
        if (current == null || proposed == null) {
            return null;
        }
        if (negativeEmptyFail && proposed < current) {
            return "负例 EmptyRate 未达标时不应降低 " + path + "（" + current + "→" + proposed + "）";
        }
        if (positiveEmptyFail && proposed > current) {
            return "正例 EmptyRate 过高时不应提高 " + path + "（" + current + "→" + proposed + "）";
        }
        return null;
    }

    private static Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
