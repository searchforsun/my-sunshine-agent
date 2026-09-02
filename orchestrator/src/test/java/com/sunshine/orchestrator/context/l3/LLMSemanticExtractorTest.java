package com.sunshine.orchestrator.context.l3;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LLMSemanticExtractorTest {

    @Test
    void parseSegments_plainArray() {
        List<String> segments = LLMSemanticExtractor.parseSegments(
                "[\"结论：采用方案 B\", \"用户偏好 Java 17\"]");
        assertThat(segments).containsExactly("结论：采用方案 B", "用户偏好 Java 17");
    }

    @Test
    void parseSegments_abstainEmptyArray() {
        assertThat(LLMSemanticExtractor.parseSegments("[]")).isEmpty();
    }

    @Test
    void parseSegments_markdownFence() {
        List<String> segments = LLMSemanticExtractor.parseSegments(
                "```json\n[\"审批单 AS-2026-0817 已通过\"]\n```");
        assertThat(segments).containsExactly("审批单 AS-2026-0817 已通过");
    }

    @Test
    void parseSegments_noisePrefixTrimmed() {
        List<String> segments = LLMSemanticExtractor.parseSegments(
                "好的，以下是抽取结果：[\"订单 10293 状态为已发货\"]");
        assertThat(segments).containsExactly("订单 10293 状态为已发货");
    }

    @Test
    void parseSegments_nonArrayReturnsEmpty() {
        assertThat(LLMSemanticExtractor.parseSegments("{\"a\":1}")).isEmpty();
        assertThat(LLMSemanticExtractor.parseSegments("")).isEmpty();
        assertThat(LLMSemanticExtractor.parseSegments("   ")).isEmpty();
    }

    @Test
    void parseSegments_dropsOverlongAndBlankItems() {
        String longSeg = "长".repeat(600);
        List<String> segments = LLMSemanticExtractor.parseSegments(
                "[\"有效片段\", \"" + longSeg + "\", \"   \", 42]");
        assertThat(segments).containsExactly("有效片段");
    }

    @Test
    void parseSegments_trimWhitespace() {
        List<String> segments = LLMSemanticExtractor.parseSegments(
                "[\" 带空白的结论  \"]");
        assertThat(segments).containsExactly("带空白的结论");
    }

    @Test
    void buildPayload_concatenatesTurnPairs() {
        String payload = LLMSemanticExtractor.buildPayload(List.of(
                new LLMSemanticExtractor.TurnPair("u1", "t1", "c1", "chat",
                        "帮我看看报销进度", "报销单 RE-1024 已通过审批", 1000L)));
        assertThat(payload).contains("user: 帮我看看报销进度");
        assertThat(payload).contains("assistant: 报销单 RE-1024 已通过审批");
    }

    @Test
    void buildPayload_skipsBlankSides() {
        String payload = LLMSemanticExtractor.buildPayload(List.of(
                new LLMSemanticExtractor.TurnPair("u1", "t1", "c1", "chat",
                        "", "仅助手侧有内容", 1000L)));
        assertThat(payload).doesNotContain("user:");
        assertThat(payload).contains("assistant: 仅助手侧有内容");
    }

    @Test
    void extractJsonArray_fenceRemoval() {
        assertThat(LLMSemanticExtractor.extractJsonArray("```json\n[\"a\"]\n```"))
                .isEqualTo("[\"a\"]");
    }

    @Test
    void extractJsonArray_proseWrapped() {
        assertThat(LLMSemanticExtractor.extractJsonArray("前缀 [\"a\",\"b\"] 后缀"))
                .isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void parseSegmentsByPair_twoDimArray_returnsPerPair() {
        List<List<String>> perPair = LLMSemanticExtractor.parseSegmentsByPair(
                "[[\"结论：方案 B\"], [], [\"订单 10293 已发货\", \"用户偏好 Java\"]]");
        assertThat(perPair).hasSize(3);
        assertThat(perPair.get(0)).containsExactly("结论：方案 B");
        assertThat(perPair.get(1)).isEmpty();
        assertThat(perPair.get(2)).containsExactly("订单 10293 已发货", "用户偏好 Java");
    }

    @Test
    void parseSegmentsByPair_flatArray_treatedAsFirstPair() {
        List<List<String>> perPair = LLMSemanticExtractor.parseSegmentsByPair(
                "[\"结论：采用方案 B\", \"用户偏好 Java 17\"]");
        assertThat(perPair).hasSize(1);
        assertThat(perPair.get(0)).containsExactly("结论：采用方案 B", "用户偏好 Java 17");
    }

    @Test
    void parseSegmentsByPair_markdownFence() {
        List<List<String>> perPair = LLMSemanticExtractor.parseSegmentsByPair(
                "```json\n[[\"审批单 AS-2026-0817 已通过\"]]\n```");
        assertThat(perPair).containsExactly(List.of("审批单 AS-2026-0817 已通过"));
    }

    @Test
    void parseSegmentsByPair_emptyAndMalformedReturnsEmpty() {
        assertThat(LLMSemanticExtractor.parseSegmentsByPair("")).isEmpty();
        assertThat(LLMSemanticExtractor.parseSegmentsByPair("{\"a\":1}")).isEmpty();
    }

    @Test
    void alignTo_padsMissingPairsAsAbstain() {
        List<List<String>> aligned = LLMSemanticExtractor.alignTo(
                List.of(List.of("a")), 3);
        assertThat(aligned).hasSize(3);
        assertThat(aligned.get(0)).containsExactly("a");
        assertThat(aligned.get(1)).isEmpty();
        assertThat(aligned.get(2)).isEmpty();
    }

    // v28 方案2：L2 对账——与 L2 结构化内容重复的语义段 abstain，避免 L3 无增量重复

    @Test
    void overlapsL2_strongHit_wholeValueContained() {
        // 语义段完整包含某条 L2 stateValue → 判定重复
        assertThat(LLMSemanticExtractor.overlapsL2(
                "用户当前在北京，明天去上海",
                java.util.Set.of("用户当前在北京，明天去上海"))).isTrue();
    }

    @Test
    void overlapsL2_shortValueIgnored() {
        // 信号过短（<4 字）不判定，防空泛误伤
        assertThat(LLMSemanticExtractor.overlapsL2(
                "用户偏好简洁回答",
                java.util.Set.of("简洁"))).isFalse();
    }

    @Test
    void overlapsL2_noSignal_kept() {
        // 无信号命中 → 保留
        assertThat(LLMSemanticExtractor.overlapsL2(
                "审批单 AS-2026-0817 已通过",
                java.util.Set.of("用户当前在北京"))).isFalse();
    }

    @Test
    void overlapsL2_emptySegEmptySignals_false() {
        assertThat(LLMSemanticExtractor.overlapsL2("", java.util.Set.of("用户偏好"))).isFalse();
        assertThat(LLMSemanticExtractor.overlapsL2("用户偏好简洁回答", java.util.Set.of())).isFalse();
    }

    @Test
    void filterAgainstL2_dropsDuplicatedKeepsIncremental() {
        Set<String> l2Covered = java.util.Set.of(
                "用户当前在北京，明天去上海",
                "用户偏好简洁回答");
        List<String> segments = java.util.List.of(
                "用户当前在北京，明天去上海",
                "用户偏好简洁回答",
                "审批单 AS-2026-0817 已通过，金额 1200 元");
        List<String> kept = LLMSemanticExtractor.filterAgainstL2(segments, l2Covered);
        // 前两条与 L2 重复被拦截；第三条有增量保留
        assertThat(kept).containsExactly("审批单 AS-2026-0817 已通过，金额 1200 元");
    }

    @Test
    void filterAgainstL2_nullOrEmptyUnchanged() {
        assertThat(LLMSemanticExtractor.filterAgainstL2(List.of(), java.util.Set.of("a"))).isEmpty();
        assertThat(LLMSemanticExtractor.filterAgainstL2(null, java.util.Set.of("a"))).isNull();
        List<String> segs = java.util.List.of("片");
        assertThat(LLMSemanticExtractor.filterAgainstL2(segs, java.util.Set.of())).isEqualTo(segs);
    }
}
