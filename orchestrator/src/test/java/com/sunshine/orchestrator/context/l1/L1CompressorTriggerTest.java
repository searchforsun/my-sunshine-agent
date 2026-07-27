package com.sunshine.orchestrator.context.l1;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class L1CompressorTriggerTest {

    private final TokenEstimator estimator = new TokenEstimator();

    private ContextProperties.L1 l1() {
        ContextProperties.L1 l1 = new ContextProperties.L1();
        l1.setMaxTokensRatio(0.8);
        l1.setTurnBackstop(40);
        l1.setTokenSafetyFactor(1.1);
        return l1;
    }

    private List<SessionTurn> rounds(int n, String content) {
        List<SessionTurn> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(SessionTurn.of("u" + i, "user", content));
            out.add(SessionTurn.of("a" + i, "assistant", content));
        }
        return out;
    }

    @Test
    void shouldCompress_tokenOverThreshold() {
        // window=1000，ratio=0.8 → 阈值 800；effective = raw×1.1 > 800 → raw > 727
        List<SessionTurn> history = rounds(3, "word ".repeat(400));
        assertThat(L1Compressor.shouldCompress(history, l1(), 1000, estimator)).isTrue();
    }

    @Test
    void shouldNotCompress_tokenUnderThresholdAndRoundsUnderBackstop() {
        // 5 轮短消息，token 远低于阈值，轮数 < 40
        List<SessionTurn> history = rounds(5, "hi");
        assertThat(L1Compressor.shouldCompress(history, l1(), 1000, estimator)).isFalse();
    }

    @Test
    void shouldCompress_roundsOverBackstopEvenIfTokenLow() {
        // 45 轮极短消息，token 低，但轮数 > 40 兜底触发
        List<SessionTurn> history = rounds(45, "hi");
        assertThat(L1Compressor.shouldCompress(history, l1(), 1000, estimator)).isTrue();
    }

    @Test
    void shouldCompress_emptyHistory_false() {
        assertThat(L1Compressor.shouldCompress(List.of(), l1(), 1000, estimator)).isFalse();
        assertThat(L1Compressor.shouldCompress(null, l1(), 1000, estimator)).isFalse();
    }
}
