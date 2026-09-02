package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void count_nullOrEmpty_returnsZero() {
        assertThat(estimator.count((String) null)).isZero();
        assertThat(estimator.count("")).isZero();
    }

    @Test
    void count_englishText_positiveAndProportional() {
        int hello = estimator.count("hello");
        int helloWorld = estimator.count("hello world");
        assertThat(hello).isPositive();
        assertThat(helloWorld).isGreaterThan(hello);
    }

    @Test
    void count_chineseText_positive() {
        assertThat(estimator.count("你好，世界")).isPositive();
    }

    @Test
    void count_chatTurns_sumsContent() {
        List<ChatTurn> turns = List.of(
                new ChatTurn("user", "hello"),
                new ChatTurn("assistant", "hello world"));
        assertThat(estimator.count(turns))
                .isEqualTo(estimator.count("hello") + estimator.count("hello world"));
    }

    @Test
    void countAssembled_sumsAllBlocks() {
        AssembledContext ctx = new AssembledContext(
                "L2 block",
                "Far summary",
                List.of(new ChatTurn("assistant", "mid")),
                List.of(new ChatTurn("user", "near")),
                "L3 material");
        int expected = estimator.count("L2 block")
                + estimator.count("Far summary")
                + estimator.count("mid")
                + estimator.count("near")
                + estimator.count("L3 material");
        assertThat(estimator.countAssembled(ctx)).isEqualTo(expected);
    }

    @Test
    void effectiveCount_appliesSafetyFactor() {
        List<SessionTurn> history = List.of(
                SessionTurn.of("u1", "user", "hello"),
                SessionTurn.of("a1", "assistant", "hello world"));
        int raw = estimator.count("hello") + estimator.count("hello world");
        assertThat(estimator.effectiveCount(history, 1.1))
                .isEqualTo((int) Math.ceil(raw * 1.1));
    }

    @Test
    void effectiveCount_nullHistory_returnsZero() {
        assertThat(estimator.effectiveCount(null, 1.1)).isZero();
        assertThat(estimator.effectiveCount(List.of(), 1.1)).isZero();
    }
}
