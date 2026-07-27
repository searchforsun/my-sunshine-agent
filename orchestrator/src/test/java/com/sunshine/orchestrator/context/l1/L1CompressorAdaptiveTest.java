package com.sunshine.orchestrator.context.l1;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class L1CompressorAdaptiveTest {

    private final TokenEstimator estimator = new TokenEstimator();

    private ContextProperties.L1 l1() {
        ContextProperties.L1 l1 = new ContextProperties.L1();
        l1.setNearTurns(8);
        l1.setMidTurns(8);
        l1.setMaxTokensRatio(0.8);
        l1.setTokenSafetyFactor(1.1);
        l1.setMidCompressRatio(0.15);
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
    void resolveNearRounds_shortHistory_keepsDefault() {
        // 短对话，token 远低于阈值，Near 保持默认 8
        List<SessionTurn> history = rounds(10, "hi");
        int near = L1Compressor.resolveNearRounds(history, l1(), 100_000, estimator, "", "");
        assertThat(near).isEqualTo(8);
    }

    @Test
    void resolveNearRounds_longNear_shrinksBelowDefault() {
        // Near 原文超长导致组装超阈值，Near 应缩小
        // window=200，ratio=0.8 → 阈值 160；每轮约 50 token，8 轮 near ≈ 400 token 超阈值
        List<SessionTurn> history = rounds(10, "word ".repeat(12));
        int near = L1Compressor.resolveNearRounds(history, l1(), 200, estimator, "", "");
        assertThat(near).isLessThan(8);
        assertThat(near).isGreaterThanOrEqualTo(1);
    }

    @Test
    void resolveNearRounds_extremeLong_neverBelowOne() {
        // 极端超长，Near 缩到 1 轮（保当前交互完整）
        List<SessionTurn> history = rounds(10, "word ".repeat(500));
        int near = L1Compressor.resolveNearRounds(history, l1(), 100, estimator, "", "");
        assertThat(near).isEqualTo(1);
    }
}
