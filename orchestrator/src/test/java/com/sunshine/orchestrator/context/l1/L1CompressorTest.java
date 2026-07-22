package com.sunshine.orchestrator.context.l1;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class L1CompressorTest {

    @Mock
    private LlmGatewayClient llm;
    @Mock
    private ConversationContextL1Store store;
    @Mock
    private PromptCatalogHolder catalogHolder;

    private ContextProperties properties;
    private L1Compressor compressor;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        properties.getL1().setNearTurns(2);
        properties.getL1().setMidTurns(2);
        properties.getL1().setMaxChars(100_000);
        compressor = new L1Compressor(properties, llm, store, catalogHolder);
        lenient().when(catalogHolder.requireText("context.l1.mid-compress"))
                .thenReturn("mid-system");
        lenient().when(catalogHolder.requireText("context.l1.far-fold"))
                .thenReturn("far-system");
        lenient().when(store.find(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void shouldCompress_whenOverMaxCharsEvenIfUnderTurnCap() {
        List<SessionTurn> turns = List.of(
                SessionTurn.of("u1", "user", "a".repeat(40)),
                SessionTurn.of("a1", "assistant", "b".repeat(40)),
                SessionTurn.of("u2", "user", "c".repeat(40)));
        assertThat(turns).hasSizeLessThan(2 + 2);
        assertThat(L1Compressor.shouldCompress(turns, 2, 2, 100)).isTrue();
    }

    @Test
    void shouldCompress_whenOverTurnCapEvenIfUnderChars() {
        List<SessionTurn> turns = IntStream.range(0, 10)
                .mapToObj(i -> SessionTurn.of("m" + i, i % 2 == 0 ? "user" : "assistant", "x"))
                .toList();
        assertThat(L1Compressor.shouldCompress(turns, 2, 2, 100_000)).isTrue();
    }

    @Test
    void shouldNotCompress_whenUnderBothCaps() {
        List<SessionTurn> turns = List.of(
                SessionTurn.of("u1", "user", "hi"),
                SessionTurn.of("a1", "assistant", "hello"));
        assertThat(L1Compressor.shouldCompress(turns, 2, 2, 100_000)).isFalse();
    }

    @Test
    void compress_writesMidAnswersAndFarSummary() {
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("摘要A");
        when(llm.complete(eq("far-system"), anyString())).thenReturn("远窗摘要");
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "long answer 0"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "long answer 1"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "long answer 2"));

        compressor.compress("u", "default", "c1", history);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> midCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> farCaptor = ArgumentCaptor.forClass(String.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), farCaptor.capture(), eq(2), eq(2));
        assertThat(midCaptor.getValue()).containsEntry("a1", "摘要A");
        assertThat(farCaptor.getValue()).isEqualTo("远窗摘要");
        verify(llm).complete(eq("mid-system"), anyString());
        verify(llm).complete(eq("far-system"), anyString());
    }

    @Test
    void compress_skipsWhenUnderCaps() {
        List<SessionTurn> history = List.of(
                SessionTurn.of("u1", "user", "hi"),
                SessionTurn.of("a1", "assistant", "ok"));

        compressor.compress("u", "default", "c1", history);

        verify(store, never()).upsert(anyString(), anyString(), anyString(), anyMap(), anyString(), anyInt(), anyInt());
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void compress_reusesExistingMidAnswers() {
        Map<String, String> existing = new HashMap<>();
        existing.put("a1", "已有摘要");
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        entity.setMidAnswers("{\"a1\":\"已有摘要\"}");
        entity.setFarSummary("旧远窗");
        when(store.find("c1")).thenReturn(Optional.of(entity));
        when(store.parseMidAnswers(any())).thenReturn(existing);
        when(store.farSummaryOf(any())).thenReturn("旧远窗");
        when(llm.complete(eq("far-system"), anyString())).thenReturn("新远窗");

        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "A0"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "A1 full"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "A2"));

        compressor.compress("u", "default", "c1", history);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> midCaptor = ArgumentCaptor.forClass(Map.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), anyString(), eq(2), eq(2));
        assertThat(midCaptor.getValue()).containsEntry("a1", "已有摘要");
        verify(llm, never()).complete(eq("mid-system"), anyString());
    }
}
