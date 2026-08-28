package com.sunshine.orchestrator.context.l3;

import com.sunshine.orchestrator.context.ContextProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class L3IngestServiceTest {

    @Mock
    private HistoryRagClient historyRagClient;
    @Mock
    private LLMSemanticExtractor semanticExtractor;

    private ContextProperties properties;
    private L3IngestService service;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        properties.setEnabled(true);
        properties.getL3().setSemanticExtractEnabled(true);
        properties.getL3().setSemanticBatchTurns(3);
        properties.getL3().setProcessLayerEnabled(true);
        service = new L3IngestService(properties, historyRagClient, semanticExtractor);
    }

    private void accumulateThreePairs() {
        service.accumulateTurnPair("u1", "t1", "c1", "chat", "um1", "q1", "am1", "a1", 1000L);
        service.accumulateTurnPair("u1", "t1", "c1", "chat", "um2", "q2", "am2", "a2", 1000L);
        service.accumulateTurnPair("u1", "t1", "c1", "chat", "um3", "q3", "am3", "a3", 1000L);
    }

    @Test
    void ingest_bodyLayer_upsertsWithScene() {
        when(historyRagClient.upsert(eq("u1"), eq("t1"), eq("c1"), eq("m1"), eq("正文"),
                eq(1000L), eq("task"), eq("body"), eq(true)))
                .thenReturn(Mono.empty());

        service.ingest("u1", "t1", "c1", "m1", " 正文 ", 1000L, "task");

        verify(historyRagClient).upsert("u1", "t1", "c1", "m1", "正文", 1000L, "task", "body", true);
    }

    @Test
    void ingest_bodyLayer_dedupeOffWhenSemanticDedupeDisabled() {
        properties.getL3().setSemanticDedupeEnabled(false);
        when(historyRagClient.upsert(eq("u1"), eq("t1"), eq("c1"), eq("m1"), eq("正文"),
                eq(1000L), eq("task"), eq("body"), eq(false)))
                .thenReturn(Mono.empty());

        service.ingest("u1", "t1", "c1", "m1", " 正文 ", 1000L, "task");

        verify(historyRagClient).upsert("u1", "t1", "c1", "m1", "正文", 1000L, "task", "body", false);
    }

    @Test
    void ingest_disabled_skips() {
        properties.setEnabled(false);
        service.ingest("u1", "t1", "c1", "m1", "正文", 1000L, "chat");
        verify(historyRagClient, never()).upsert(any(), any(), any(), any(), any(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void ingestTurnPair_gateOn_noImmediateBodyUpsert() {
        service.ingestTurnPair("u1", "t1", "c1", "chat", "um1", "q1", "am1", "a1", 1000L);
        verify(historyRagClient, never()).upsert(any(), any(), any(), any(), any(), anyLong(), any(), any(), anyBoolean());
        verify(semanticExtractor, never()).extractByPair(anyList());
    }

    @Test
    void ingestTurnPair_gateOffImmediateBodyUpsert_taskScene() {
        properties.getL3().setBodyGateEnabled(false);
        when(historyRagClient.upsert(eq("u1"), eq("t1"), eq("c1"), eq("um1"), eq("q1"),
                eq(1000L), eq("task"), eq("body"), eq(true)))
                .thenReturn(Mono.empty());
        when(historyRagClient.upsert(eq("u1"), eq("t1"), eq("c1"), eq("am1"), eq("a1"),
                eq(1000L), eq("task"), eq("body"), eq(true)))
                .thenReturn(Mono.empty());

        service.ingestTurnPair("u1", "t1", "c1", "task", "um1", "q1", "am1", "a1", 1000L);

        verify(historyRagClient).upsert(eq("u1"), eq("t1"), eq("c1"), eq("um1"), eq("q1"),
                eq(1000L), eq("task"), eq("body"), eq(true));
        verify(historyRagClient).upsert(eq("u1"), eq("t1"), eq("c1"), eq("am1"), eq("a1"),
                eq(1000L), eq("task"), eq("body"), eq(true));
    }

    @Test
    void ingestTurnPair_chatScene_noBodyEvenWhenGateOff() {
        // v28：chat 场景 body 原文层退役，即使 gateBody 关闭也不写 body，仅攒批走语义摘要
        properties.getL3().setBodyGateEnabled(false);
        service.ingestTurnPair("u1", "t1", "c1", "chat", "um1", "q1", "am1", "a1", 1000L);
        verify(historyRagClient, never()).upsert(any(), any(), any(), any(), any(), anyLong(), anyString(), anyString(), anyBoolean());
        verify(semanticExtractor, never()).extractByPair(anyList());
    }

    @Test
    void accumulateTurnPair_belowBatchTurns_noFlush() {
        service.accumulateTurnPair("u1", "t1", "c1", "chat", "um1", "q1", "am1", "a1", 1000L);
        service.accumulateTurnPair("u1", "t1", "c1", "chat", "um2", "q2", "am2", "a2", 2000L);
        verify(semanticExtractor, never()).extractByPair(anyList());
    }

    @Test
    void accumulateTurnPair_reachesBatchTurns_triggersFlush() {
        when(semanticExtractor.extractByPair(anyList())).thenReturn(List.of());
        accumulateThreePairs();
        verify(semanticExtractor).extractByPair(anyList());
    }

    @Test
    void accumulateTurnPair_disabled_skips() {
        properties.getL3().setSemanticExtractEnabled(false);
        accumulateThreePairs();
        verify(semanticExtractor, never()).extractByPair(anyList());
    }

    @Test
    void flush_semanticSegments_upsertWithSemanticLayerAndDedupe() {
        properties.getL3().setBodyGateEnabled(false);
        when(semanticExtractor.extractByPair(anyList()))
                .thenReturn(List.of(List.of("结论：采用方案 B", "审批单 AS-2026-0817 已通过")));
        when(historyRagClient.upsert(eq("u1"), eq("t1"), eq("c1"), anyString(), anyString(),
                eq(1000L), eq("chat"), eq("semantic"), eq(true)))
                .thenReturn(Mono.empty());

        accumulateThreePairs();

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(historyRagClient, times(2)).upsert(
                eq("u1"), eq("t1"), eq("c1"), anyString(), contentCaptor.capture(),
                eq(1000L), eq("chat"), eq("semantic"), eq(true));
        assertThat(contentCaptor.getAllValues())
                .containsExactly("结论：采用方案 B", "审批单 AS-2026-0817 已通过");
    }

    @Test
    void flush_emptySegments_skipsUpsert() {
        when(semanticExtractor.extractByPair(anyList())).thenReturn(List.of());
        accumulateThreePairs();
        verify(historyRagClient, never()).upsert(any(), any(), any(), any(), any(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void flush_gateOn_chatScene_semanticOnlyNoBody() {
        // v28：chat 场景 flush 仅落 semantic 摘要，不再双写 body（杜绝 user/assistant 零散块）
        when(semanticExtractor.extractByPair(anyList()))
                .thenReturn(List.of(List.of("结论：方案 B"), List.of(), List.of()));
        when(historyRagClient.upsert(eq("u1"), eq("t1"), eq("c1"), anyString(), anyString(),
                eq(1000L), eq("chat"), eq("semantic"), eq(true)))
                .thenReturn(Mono.empty());

        accumulateThreePairs();

        ArgumentCaptor<String> msgIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(historyRagClient, times(1)).upsert(
                eq("u1"), eq("t1"), eq("c1"), msgIdCaptor.capture(), anyString(),
                eq(1000L), eq("chat"), eq("semantic"), eq(true));
        // 仅重要轮（第 1 轮）落 semantic 摘要；噪音轮（2/3）全部跳过；无 body 写入
        assertThat(msgIdCaptor.getValue()).isEqualTo("sem:c1:1000:0");
        verify(historyRagClient, never()).upsert(any(), any(), any(), any(), any(), anyLong(), anyString(), eq("body"), anyBoolean());
    }

    @Test
    void flush_gateOn_taskScene_bodyAndSemantic() {
        // task 场景保留 body 双写 + semantic（session_search 深挖原文依赖）
        when(semanticExtractor.extractByPair(anyList()))
                .thenReturn(List.of(List.of("结论：方案 B"), List.of(), List.of()));
        when(historyRagClient.upsert(eq("u1"), eq("t1"), eq("c1"), anyString(), anyString(),
                eq(1000L), eq("task"), anyString(), anyBoolean()))
                .thenReturn(Mono.empty());

        service.accumulateTurnPair("u1", "t1", "c1", "task", "um1", "q1", "am1", "a1", 1000L);
        service.accumulateTurnPair("u1", "t1", "c1", "task", "um2", "q2", "am2", "a2", 1000L);
        service.accumulateTurnPair("u1", "t1", "c1", "task", "um3", "q3", "am3", "a3", 1000L);

        ArgumentCaptor<String> layerCaptor = ArgumentCaptor.forClass(String.class);
        verify(historyRagClient, times(3)).upsert(
                eq("u1"), eq("t1"), eq("c1"), anyString(), anyString(),
                eq(1000L), eq("task"), layerCaptor.capture(), anyBoolean());
        assertThat(layerCaptor.getAllValues()).containsExactly("body", "body", "semantic");
    }

    @Test
    void flush_gateOn_allNoise_skipsBodyAndSemantic() {
        when(semanticExtractor.extractByPair(anyList()))
                .thenReturn(List.of(List.of(), List.of(), List.of()));
        accumulateThreePairs();
        verify(historyRagClient, never()).upsert(any(), any(), any(), any(), any(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void ingestProcess_ingestsResultTruncatedTo200Chars() {
        when(historyRagClient.upsert(eq("u1"), eq("t1"), eq("c1"), anyString(), anyString(),
                eq(1000L), eq("task"), eq("process"), eq(true)))
                .thenReturn(Mono.empty());
        String longResult = "工".repeat(300);
        String stepsJson = "[{\"label\":\"查询报销\",\"result\":\"" + longResult + "\"}]";

        service.ingestProcess("u1", "t1", "c1", "m1", stepsJson, 1000L);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(historyRagClient).upsert(
                eq("u1"), eq("t1"), eq("c1"), anyString(), contentCaptor.capture(),
                eq(1000L), eq("task"), eq("process"), eq(true));
        String content = contentCaptor.getValue();
        assertThat(content).startsWith("查询报销: ");
        assertThat(content.length()).isLessThanOrEqualTo("查询报销: ".length() + 200);
    }

    @Test
    void ingestProcess_disabled_skips() {
        properties.getL3().setProcessLayerEnabled(false);
        service.ingestProcess("u1", "t1", "c1", "m1",
                "[{\"label\":\"查\",\"result\":\"r\"}]", 1000L);
        verify(historyRagClient, never()).upsert(any(), any(), any(), any(), any(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    void flushDuePairs_emptyBuffer_noop() {
        service.flushDuePairs();
        verify(semanticExtractor, never()).extractByPair(anyList());
    }

    @Test
    void flush_reentrant_sameConvNotDoubleFlush() {
        when(semanticExtractor.extractByPair(anyList())).thenReturn(List.of(List.of("seg")));
        when(historyRagClient.upsert(anyString(), anyString(), anyString(), anyString(), anyString(),
                eq(1000L), anyString(), anyString(), anyBoolean()))
                .thenReturn(Mono.empty());

        accumulateThreePairs();
        // flush 中再次触发同 conv（模拟并发调用）→ 不重复执行
        service.flushDuePairs();

        verify(semanticExtractor, times(1)).extractByPair(anyList());
    }

    @Test
    void flush_failure_swallowed() {
        when(semanticExtractor.extractByPair(anyList())).thenThrow(new RuntimeException("llm down"));
        accumulateThreePairs();
        // 不抛异常即通过
        verify(semanticExtractor).extractByPair(anyList());
    }
}
