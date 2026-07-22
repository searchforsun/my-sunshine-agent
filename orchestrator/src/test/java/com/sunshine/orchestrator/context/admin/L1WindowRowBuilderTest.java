package com.sunshine.orchestrator.context.admin;

import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.L1WindowRowView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class L1WindowRowBuilderTest {

    @Test
    void build_nearMidFar_newestFirst_midUsesSummary() {
        Instant t0 = Instant.parse("2026-07-22T01:00:00Z");
        Instant t1 = Instant.parse("2026-07-22T02:00:00Z");
        Instant t2 = Instant.parse("2026-07-22T03:00:00Z");
        Instant tFar = Instant.parse("2026-07-22T04:00:00Z");
        // 3 轮；near=1 mid=1 → near=r2, mid=r1, far=r0（远窗只显示摘要行）
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "A0 long"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "A1 long"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "A2 long"));
        Map<String, Instant> times = Map.of(
                "u0", t0, "a0", t0,
                "u1", t1, "a1", t1,
                "u2", t2, "a2", t2);
        Map<String, String> mid = Map.of("a1", "摘要A1");

        List<L1WindowRowView> rows = L1WindowRowBuilder.build(
                history, times, mid, "远窗总摘要", tFar, 1, 1);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).band()).isEqualTo("near");
        assertThat(rows.get(0).index()).isEqualTo(1);
        assertThat(rows.get(0).userText()).isEqualTo("Q2");
        assertThat(rows.get(0).assistantText()).isEqualTo("A2 long");
        assertThat(rows.get(0).assistantSummarized()).isFalse();
        assertThat(rows.get(0).at()).isEqualTo(t2);

        assertThat(rows.get(1).band()).isEqualTo("mid");
        assertThat(rows.get(1).userText()).isEqualTo("Q1");
        assertThat(rows.get(1).assistantText()).isEqualTo("摘要A1");
        assertThat(rows.get(1).assistantSummarized()).isTrue();
        assertThat(rows.get(1).at()).isEqualTo(t1);

        assertThat(rows.get(2).band()).isEqualTo("far");
        assertThat(rows.get(2).userText()).isNull();
        assertThat(rows.get(2).assistantText()).isEqualTo("远窗总摘要");
        assertThat(rows.get(2).at()).isEqualTo(tFar);
    }

    @Test
    void build_nineRounds_near8_showsOneMid() {
        List<SessionTurn> history = IntStream.range(0, 9)
                .mapToObj(i -> List.of(
                        SessionTurn.of("u" + i, "user", "Q" + i),
                        SessionTurn.of("a" + i, "assistant", "A" + i)))
                .flatMap(List::stream)
                .toList();
        Map<String, String> mid = Map.of("a0", "S0");

        List<L1WindowRowView> rows = L1WindowRowBuilder.build(
                history, Map.of(), mid, "", null, 8, 8);

        assertThat(rows).hasSize(9);
        assertThat(rows.get(0).band()).isEqualTo("near");
        assertThat(rows.get(0).userText()).isEqualTo("Q8");
        assertThat(rows.get(7).band()).isEqualTo("near");
        assertThat(rows.get(7).userText()).isEqualTo("Q1");
        assertThat(rows.get(8).band()).isEqualTo("mid");
        assertThat(rows.get(8).userText()).isEqualTo("Q0");
        assertThat(rows.get(8).assistantText()).isEqualTo("S0");
    }
}
