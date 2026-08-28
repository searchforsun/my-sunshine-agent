package com.sunshine.orchestrator.context.l3;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class L3RecallServiceTest {

    @Mock
    private HistoryRagClient historyRagClient;
    @Mock
    private PromptCatalogHolder catalogHolder;

    private ContextProperties properties;
    private L3RecallService recall;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        properties.getL3().setTopK(5);
        properties.getL3().setMinScore(0.55);
        properties.getL3().setTimeDecay(false);
        recall = new L3RecallService(properties, historyRagClient, catalogHolder);
        lenient().when(catalogHolder.requireText(L3RecallService.MATERIAL_HEADER))
                .thenReturn("[历史材料 · L3 · 可能过期]");
    }

    @Test
    void recall_excludesMessageIdsAlreadyInL1Window() {
        when(historyRagClient.search(eq("u1"), eq("default"), isNull(String.class), eq("chat"),
                eq(List.of("semantic")), eq("budget"), anyInt()))
                .thenReturn(Mono.just(List.of(
                        new HistoryRagClient.HistoryHit("c1", "msg-near", "近窗内容不应出现", 0.95f, 1L),
                        new HistoryRagClient.HistoryHit("c0", "msg-old", "旧会话预算约定", 0.9f, 1L))));

        String block = recall.recall(
                "u1", "default", "budget",
                Set.of("msg-near"),
                Set.of(),
                false);

        assertThat(block).doesNotContain("近窗内容不应出现");
        assertThat(block).doesNotContain("msg-near");
        assertThat(block).contains("旧会话预算约定");
        assertThat(block).startsWith("[历史材料 · L3 · 可能过期]");
    }

    @Test
    void recall_farBackfill_includesFarHitsWhenSummaryPresent() {
        // Far 命中降权 0.92×0.5=0.46，需 minScore ≤ 0.46 才能保留
        properties.getL3().setMinScore(0.4);
        when(historyRagClient.search(anyString(), anyString(), isNull(String.class), eq("chat"),
                eq(List.of("semantic")), anyString(), anyInt()))
                .thenReturn(Mono.just(List.of(
                        new HistoryRagClient.HistoryHit("c1", "msg-far", "远窗细节：合同金额 50 万", 0.92f, 1L))));

        String block = recall.recall(
                "u1", "default", "合同",
                Set.of("msg-near"),
                Set.of("msg-far"),
                true);

        assertThat(block).contains("远窗细节：合同金额 50 万");
    }

    @Test
    void filterAndRank_appliesTimeDecay() {
        ContextProperties.L3 l3 = new ContextProperties.L3();
        l3.setMinScore(0.01);
        l3.setTimeDecay(true);
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        long oldMs = Instant.parse("2026-04-22T00:00:00Z").toEpochMilli(); // ~91 days ≈ 0.5 score（90 天半衰期）
        List<L3RecallService.ScoredHit> kept = L3RecallService.filterAndRank(
                List.of(new HistoryRagClient.HistoryHit("c", "m1", "old fact", 0.8f, oldMs)),
                Set.of(),
                Set.of(),
                false,
                l3,
                now);
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).score()).isLessThan(0.8);
        assertThat(kept.get(0).score()).isGreaterThan(0.05);
    }

    @Test
    void filterAndRank_farHit_isDownWeightedNotExcluded() {
        ContextProperties.L3 l3 = new ContextProperties.L3();
        l3.setMinScore(0.3);
        l3.setTimeDecay(false);
        // Far 已折叠 msgId 命中：score 0.92 × 0.5 = 0.46 ≥ minScore 0.3 → 保留（非硬排除）
        List<L3RecallService.ScoredHit> kept = L3RecallService.filterAndRank(
                List.of(new HistoryRagClient.HistoryHit("c1", "msg-far", "远窗细节", 0.92f, 1L)),
                Set.of(),
                Set.of("msg-far"),
                true,
                l3,
                Instant.now());
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).score()).isCloseTo(0.46, org.assertj.core.data.Offset.offset(0.001));
        assertThat(kept.get(0).farBackfill()).isTrue();
    }

    @Test
    void filterAndRank_farHit_belowMinScoreAfterDownWeight_excluded() {
        ContextProperties.L3 l3 = new ContextProperties.L3();
        l3.setMinScore(0.5);
        l3.setTimeDecay(false);
        // score 0.92 × 0.5 = 0.46 < minScore 0.5 → 降权后被过滤
        List<L3RecallService.ScoredHit> kept = L3RecallService.filterAndRank(
                List.of(new HistoryRagClient.HistoryHit("c1", "msg-far", "远窗细节", 0.92f, 1L)),
                Set.of(),
                Set.of("msg-far"),
                true,
                l3,
                Instant.now());
        assertThat(kept).isEmpty();
    }
}
