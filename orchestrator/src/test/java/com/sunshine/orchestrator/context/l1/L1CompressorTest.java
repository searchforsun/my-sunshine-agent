package com.sunshine.orchestrator.context.l1;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.ModelWindowCache;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.TokenEstimator;
import com.sunshine.orchestrator.context.l2.L2StateStore;
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
import static org.mockito.ArgumentMatchers.anyDouble;
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
    private L2StateStore l2StateStore;
    @Mock
    private PromptCatalogHolder catalogHolder;
    @Mock
    private TokenEstimator tokenEstimator;
    @Mock
    private ModelWindowCache modelWindowCache;

    private ContextProperties properties;
    private L1Compressor compressor;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        properties.getL1().setNearTurns(2);
        properties.getL1().setMidTurns(2);
        // 轮数兜底触发旧压缩测试（测试用 5-6 轮，backstop=4 保证触发）
        properties.getL1().setTurnBackstop(4);
        compressor = new L1Compressor(properties, llm, store, l2StateStore, catalogHolder,
                tokenEstimator, modelWindowCache);
        lenient().when(modelWindowCache.windowFor(any())).thenReturn(256000);
        // 默认低 token（远低于阈值），靠轮数兜底触发
        lenient().when(tokenEstimator.effectiveCount(any(), anyDouble())).thenReturn(10);
        lenient().when(catalogHolder.requireText("context.l1.mid-compress"))
                .thenReturn("mid-system");
        lenient().when(catalogHolder.requireText("context.l1.far-fold"))
                .thenReturn("far-system");
        lenient().when(store.find(anyString())).thenReturn(Optional.empty());
        lenient().when(store.parseMidAnswers(any())).thenReturn(Map.of());
        lenient().when(store.farSummaryOf(any())).thenReturn("");
        lenient().when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of());
        lenient().when(l2StateStore.assembleSystemBlock(anyString(), anyString())).thenReturn("");
    }

    @Test
    void partition_usesQaRoundsNotMessageCount() {
        // 9 轮 × 2 消息；near=8 mid=8 → 仅第 1 轮进中窗，近窗 8 轮
        List<SessionTurn> history = IntStream.range(0, 18)
                .mapToObj(i -> SessionTurn.of(
                        (i % 2 == 0 ? "u" : "a") + (i / 2),
                        i % 2 == 0 ? "user" : "assistant",
                        "t" + i))
                .toList();
        L1Compressor.WindowBands bands = L1Compressor.partition(history, 8, 8);
        assertThat(L1Compressor.countRounds(bands.near())).isEqualTo(8);
        assertThat(L1Compressor.countRounds(bands.mid())).isEqualTo(1);
        assertThat(L1Compressor.countRounds(bands.far())).isZero();
        assertThat(bands.mid().stream().filter(t -> "assistant".equals(t.role())).count()).isEqualTo(1);
    }

    @Test
    void compress_writesMidAnswersAndFarSummary() {
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("摘要A");
        when(llm.complete(eq("far-system"), anyString())).thenReturn("远窗摘要");
        // 5 轮；near=2 mid=2 → far=r0, mid=r1+r2, near=r3+r4
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "long answer 0"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "long answer 1"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "long answer 2"),
                SessionTurn.of("u3", "user", "Q3"),
                SessionTurn.of("a3", "assistant", "long answer 3"),
                SessionTurn.of("u4", "user", "Q4"),
                SessionTurn.of("a4", "assistant", "long answer 4"));

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
        assertThat(midCaptor.getValue()).containsEntry("a2", "摘要A");
        assertThat(midCaptor.getValue()).doesNotContainKey("a0");
        assertThat(farCaptor.getValue()).isEqualTo("远窗摘要");
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder("u0", "a0");
        verify(llm, times(2)).complete(eq("mid-system"), anyString());
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
        existing.put("a2", "已有摘要2");
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        entity.setMidAnswers("{\"a1\":\"已有摘要\",\"a2\":\"已有摘要2\"}");
        entity.setFarSummary("旧远窗");
        when(store.find("c1")).thenReturn(Optional.of(entity));
        when(store.parseMidAnswers(any())).thenReturn(existing);
        when(store.farSummaryOf(any())).thenReturn("旧远窗");
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of("u0", "a0"));

        // 5 轮；mid=r1+r2 已有摘要 → 不调 mid-compress；far 已折叠 → 不调 far-fold
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "A0"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "A1 full"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "A2"),
                SessionTurn.of("u3", "user", "Q3"),
                SessionTurn.of("a3", "assistant", "A3"),
                SessionTurn.of("u4", "user", "Q4"),
                SessionTurn.of("a4", "assistant", "A4"));

        compressor.compress("u", "default", "c1", history);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> midCaptor = ArgumentCaptor.forClass(Map.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), anyString(), anyCollection(), eq(2), eq(2));
        assertThat(midCaptor.getValue()).containsEntry("a1", "已有摘要");
        assertThat(midCaptor.getValue()).containsEntry("a2", "已有摘要2");
        verify(llm, never()).complete(eq("mid-system"), anyString());
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
        mid.put("a2", "Mid摘要A2");
        when(store.find("c1")).thenReturn(Optional.of(entity));
        when(store.parseMidAnswers(any())).thenReturn(mid);
        when(store.farSummaryOf(any())).thenReturn("旧远窗摘要");
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of("u0", "a0"));
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("摘要新");
        when(llm.complete(eq("far-system"), anyString())).thenReturn("叠加远窗");

        // 5 轮 near=2 mid=2 → far=r0, mid=r1+r2, near=r3+r4；r0 已折叠，无新 Far
        // 再加第 6 轮后 far=r0+r1 → 仅折叠 r1
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "UNIQUE_FAR_OLD_Q0"),
                SessionTurn.of("a0", "assistant", "UNIQUE_FAR_OLD_A0"),
                SessionTurn.of("u1", "user", "UNIQUE_NEW_FAR_Q1"),
                SessionTurn.of("a1", "assistant", "UNIQUE_NEW_FAR_A1"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "A2 full"),
                SessionTurn.of("u3", "user", "Q3"),
                SessionTurn.of("a3", "assistant", "A3"),
                SessionTurn.of("u4", "user", "Q4"),
                SessionTurn.of("a4", "assistant", "A4"),
                SessionTurn.of("u5", "user", "Q5"),
                SessionTurn.of("a5", "assistant", "A5"));

        compressor.compress("u", "default", "c1", history);

        ArgumentCaptor<String> farUserCaptor = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(eq("far-system"), farUserCaptor.capture());
        String farPrompt = farUserCaptor.getValue();
        assertThat(farPrompt).contains("旧远窗摘要");
        assertThat(farPrompt).contains("UNIQUE_NEW_FAR_Q1");
        assertThat(farPrompt).contains("UNIQUE_NEW_FAR_A1");
        assertThat(farPrompt).contains("【现行 L2 用户状态 · 权威】");
        assertThat(farPrompt).doesNotContain("UNIQUE_FAR_OLD_Q0");
        assertThat(farPrompt).doesNotContain("UNIQUE_FAR_OLD_A0");
        verify(l2StateStore).assembleSystemBlock("u", "default");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> midCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> foldedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), eq("叠加远窗"), foldedCaptor.capture(), eq(2), eq(2));
        // mid = r2+r3 → a2 复用, a3 新建；a1 已进 Far
        assertThat(midCaptor.getValue()).doesNotContainKey("a1");
        assertThat(midCaptor.getValue()).containsEntry("a2", "Mid摘要A2");
        assertThat(midCaptor.getValue()).containsEntry("a3", "摘要新");
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder("u0", "a0", "u1", "a1");
    }

    @Test
    void compress_skipsFarFoldWhenNoNewFarTurns() {
        when(store.find("c1")).thenReturn(Optional.of(new ConversationContextL1Entity()));
        when(store.farSummaryOf(any())).thenReturn("稳定远窗");
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of("u0", "a0"));
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("摘要");

        // 5 轮；far=r0 已全部 folded
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "Q0"),
                SessionTurn.of("a0", "assistant", "A0"),
                SessionTurn.of("u1", "user", "Q1"),
                SessionTurn.of("a1", "assistant", "A1"),
                SessionTurn.of("u2", "user", "Q2"),
                SessionTurn.of("a2", "assistant", "A2"),
                SessionTurn.of("u3", "user", "Q3"),
                SessionTurn.of("a3", "assistant", "A3"),
                SessionTurn.of("u4", "user", "Q4"),
                SessionTurn.of("a4", "assistant", "A4"));

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
                SessionTurn.of("a2", "assistant", "A2"),
                SessionTurn.of("u3", "user", "Q3"),
                SessionTurn.of("a3", "assistant", "A3"),
                SessionTurn.of("u4", "user", "Q4"),
                SessionTurn.of("a4", "assistant", "A4"));

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
