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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        lenient().when(store.parseMidAnswers(any())).thenReturn(Map.of());
        lenient().when(store.farSummaryOf(any())).thenReturn("");
        lenient().when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of());
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
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> foldedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), farCaptor.capture(), foldedCaptor.capture(), eq(2), eq(2));
        assertThat(midCaptor.getValue()).containsEntry("a1", "摘要A");
        assertThat(farCaptor.getValue()).isEqualTo("远窗摘要");
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder("u0", "a0");
        verify(llm).complete(eq("mid-system"), anyString());
        verify(llm).complete(eq("far-system"), anyString());
    }

    @Test
    void compress_skipsWhenUnderCaps() {
        List<SessionTurn> history = List.of(
                SessionTurn.of("u1", "user", "hi"),
                SessionTurn.of("a1", "assistant", "ok"));

        compressor.compress("u", "default", "c1", history);

        verify(store, never()).upsert(
                anyString(), anyString(), anyString(), anyMap(), anyString(), anyCollection(), anyInt(), anyInt());
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
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of("u0", "a0"));

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
                midCaptor.capture(), anyString(), anyCollection(), eq(2), eq(2));
        assertThat(midCaptor.getValue()).containsEntry("a1", "已有摘要");
        verify(llm, never()).complete(eq("mid-system"), anyString());
        // Far 已折叠完 → 不再调 far-fold
        verify(llm, never()).complete(eq("far-system"), anyString());
    }

    @Test
    void compress_incrementalFarFold_doesNotResendAlreadyFolded() {
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        entity.setFarSummary("旧远窗摘要");
        entity.setFarFoldedMsgIds("[\"u0\",\"a0\"]");
        Map<String, String> mid = new HashMap<>();
        mid.put("a1", "Mid摘要A1");
        when(store.find("c1")).thenReturn(Optional.of(entity));
        when(store.parseMidAnswers(any())).thenReturn(mid);
        when(store.farSummaryOf(any())).thenReturn("旧远窗摘要");
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of("u0", "a0"));
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("摘要A2");
        when(llm.complete(eq("far-system"), anyString())).thenReturn("叠加远窗");

        // near=2 mid=2 → far=[u0,a0,u1,a1], mid=[u2,a2], near=[u3,a3]
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "UNIQUE_FAR_OLD_Q0"),
                SessionTurn.of("a0", "assistant", "UNIQUE_FAR_OLD_A0"),
                SessionTurn.of("u1", "user", "UNIQUE_NEW_FAR_Q1"),
                SessionTurn.of("a1", "assistant", "UNIQUE_NEW_FAR_A1"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "A2 full"),
                SessionTurn.of("u3", "user", "Q3"),
                SessionTurn.of("a3", "assistant", "A3"));

        compressor.compress("u", "default", "c1", history);

        ArgumentCaptor<String> farUserCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(eq("far-system"), farUserCaptor.capture());
        String farPrompt = farUserCaptor.getValue();
        assertThat(farPrompt).contains("旧远窗摘要");
        assertThat(farPrompt).contains("UNIQUE_NEW_FAR_Q1");
        assertThat(farPrompt).contains("UNIQUE_NEW_FAR_A1");
        assertThat(farPrompt).doesNotContain("UNIQUE_FAR_OLD_Q0");
        assertThat(farPrompt).doesNotContain("UNIQUE_FAR_OLD_A0");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> midCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> foldedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), eq("叠加远窗"), foldedCaptor.capture(), eq(2), eq(2));
        // a1 已进 Far → 从 mid_answers 剔除
        assertThat(midCaptor.getValue()).doesNotContainKey("a1");
        assertThat(midCaptor.getValue()).containsEntry("a2", "摘要A2");
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder("u0", "a0", "u1", "a1");
    }

    @Test
    void compress_skipsFarFoldWhenNoNewFarTurns() {
        when(store.find("c1")).thenReturn(Optional.of(new ConversationContextL1Entity()));
        when(store.farSummaryOf(any())).thenReturn("稳定远窗");
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of("u0", "a0"));
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("摘要");

        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "A0"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "A1"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "A2"));

        compressor.compress("u", "default", "c1", history);

        verify(llm, never()).complete(eq("far-system"), anyString());
        ArgumentCaptor<String> farCaptor = ArgumentCaptor.forClass(String.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                anyMap(), farCaptor.capture(), anyCollection(), eq(2), eq(2));
        assertThat(farCaptor.getValue()).isEqualTo("稳定远窗");
    }

    @Test
    void compress_serializesSameConvId() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger inCritical = new AtomicInteger(0);
        AtomicInteger maxInCritical = new AtomicInteger(0);

        when(store.find(eq("c1"))).thenAnswer(inv -> {
            int n = inCritical.incrementAndGet();
            maxInCritical.accumulateAndGet(n, Math::max);
            entered.countDown();
            assertThat(hold.await(5, TimeUnit.SECONDS)).isTrue();
            inCritical.decrementAndGet();
            return Optional.empty();
        });
        when(llm.complete(anyString(), anyString())).thenReturn("x");

        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "A0"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "A1"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "A2"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> compressor.compress("u", "default", "c1", history));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            Future<?> f2 = pool.submit(() -> compressor.compress("u", "default", "c1", history));
            Thread.sleep(150);
            assertThat(maxInCritical.get()).isEqualTo(1);
            hold.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
            assertThat(maxInCritical.get()).isEqualTo(1);
            verify(store, times(2)).find("c1");
            verify(store, atLeastOnce()).upsert(
                    anyString(), anyString(), eq("c1"), anyMap(), anyString(), anyCollection(), anyInt(), anyInt());
        } finally {
            hold.countDown();
            pool.shutdownNow();
        }
    }
}
