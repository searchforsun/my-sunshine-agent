package com.sunshine.rag.admin.eval;

import com.sunshine.rag.admin.eval.dto.ConfigSuggestionItem;
import com.sunshine.rag.admin.eval.dto.EvalSuggestResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalSuggestValidatorTest {

    @Test
    void rejectsMinRelevanceSuggestion() {
        EvalSuggestResult raw = new EvalSuggestResult(
                "诊断",
                List.of(new ConfigSuggestionItem("rerank.minRelevance", 0.25, 0.2, "放宽")),
                List.of());
        EvalSuggestResult result = EvalSuggestValidator.validate(raw, List.of());
        assertThat(result.suggestions()).isEmpty();
        assertThat(result.diagnosis()).contains("已自动过滤");
    }

    @Test
    void rejectsLoweringThresholdWhenNegativeEmptyRateFails() {
        String reason = EvalSuggestValidator.rejectReason(
                new ConfigSuggestionItem("search.minScore", 0.48, 0.42, "放宽"),
                true,
                false);
        assertThat(reason).contains("负例 EmptyRate");
        EvalSuggestResult raw = new EvalSuggestResult(
                "诊断",
                List.of(new ConfigSuggestionItem("search.minScore", 0.48, 0.42, "放宽")),
                List.of());
        EvalSuggestResult result = EvalSuggestValidator.validate(
                raw,
                List.of(EvalSuggestContextBuilder.FailureMode.NEGATIVE_EMPTY_RATE_LOW.name()));
        assertThat(result.suggestions()).isEmpty();
    }

    @Test
    void allowsRaisingThresholdWhenNegativeEmptyRateFails() {
        EvalSuggestResult raw = new EvalSuggestResult(
                "诊断",
                List.of(new ConfigSuggestionItem("search.minScore", 0.48, 0.52, "收紧")),
                List.of());
        EvalSuggestResult result = EvalSuggestValidator.validate(
                raw,
                List.of(EvalSuggestContextBuilder.FailureMode.NEGATIVE_EMPTY_RATE_LOW.name()));
        assertThat(result.suggestions()).hasSize(1);
    }

    @Test
    void rejectsRaisingThresholdWhenPositiveEmptyRateFails() {
        String reason = EvalSuggestValidator.rejectReason(
                new ConfigSuggestionItem("search.minScore", 0.48, 0.52, "收紧"),
                false,
                true);
        assertThat(reason).contains("正例 EmptyRate");
    }
}
