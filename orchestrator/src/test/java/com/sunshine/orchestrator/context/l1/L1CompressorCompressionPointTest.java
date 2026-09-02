package com.sunshine.orchestrator.context.l1;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.ModelWindowCache;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.TokenEstimator;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.registry.ModelCapabilities;
import com.sunshine.orchestrator.registry.ModelCatalogDefinition;
import com.sunshine.orchestrator.registry.ModelCatalogScene;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 压缩点模式（五层 §5.5 / task-scene §4）：task|chat × fast|pro 启用（chat 二期跟随），
 * Near 起点 = far_folded_msg_ids 之后，重组 = nearKeep 原文 + midKeep 摘要 + 其余折叠；
 * Near/Mid 参数按 kind 分化（task 2+2+Far≤10k / chat 4+4+Far，chat 无硬预算）。
 */
@ExtendWith(MockitoExtension.class)
class L1CompressorCompressionPointTest {

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
    @Mock
    private ChatConversationRepository conversationRepo;

    private ContextProperties properties;
    private L1Compressor compressor;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        // 轮次宽限兜底：6 轮 > 4 触发（避免依赖 token 估算）
        properties.getL1().setTurnBackstop(4);
        properties.getL1().getCompressionPoint().setNearKeepRounds(2);
        properties.getL1().getCompressionPoint().setMidKeepRounds(2);
        ModelSceneResolver resolver = new ModelSceneResolver(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                WebClient.builder(), "http://localhost", "default");
        resolver.replaceSnapshotForTest(
                List.of(new ModelCatalogDefinition(
                        "deepseek-v4-pro", "p", "pro", 256000, 8192, "cl100k_base",
                        ModelCapabilities.defaults(), null, true, true, 0)),
                List.of(
                        new ModelCatalogScene("chat", "deepseek-v4-pro", null, Map.of(), true),
                        new ModelCatalogScene("default", "deepseek-v4-pro", null, Map.of(), true)));
        compressor = new L1Compressor(properties, llm, store, l2StateStore, catalogHolder,
                tokenEstimator, modelWindowCache, resolver, conversationRepo);
        lenient().when(modelWindowCache.windowFor(any())).thenReturn(256000);
        lenient().when(tokenEstimator.effectiveCount(any(), anyDouble())).thenReturn(10);
        lenient().when(catalogHolder.requireText("context.l1.mid-compress")).thenReturn("mid-system");
        lenient().when(catalogHolder.requireText("context.l1.far-fold")).thenReturn("far-system");
        lenient().when(store.find(anyString())).thenReturn(Optional.empty());
        lenient().when(store.parseMidAnswers(any())).thenReturn(Map.of());
        lenient().when(store.farSummaryOf(any())).thenReturn("");
        lenient().when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of());
        // 存量行兼容：summarized 回退为折叠边界（测试默认无间隙轮）
        lenient().when(store.parseFarSummarizedMsgIds(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        lenient().when(l2StateStore.assembleWorkspaceBlock(anyString(), anyString())).thenReturn("");
        lenient().when(l2StateStore.assembleSystemBlock(anyString(), anyString())).thenReturn("");
    }

    private static ChatConversationEntity taskConv(String mode) {
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId("c1");
        conv.setKind("task");
        conv.setWorkspaceId("ws-1");
        conv.setExecutionPreference(mode);
        return conv;
    }

    private static List<SessionTurn> rounds(int n) {
        List<SessionTurn> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(SessionTurn.of("u" + i, "user", "Q" + i));
            out.add(SessionTurn.of("a" + i, "assistant", "A" + i));
        }
        return out;
    }

    @Test
    void compressionPointActive_taskAndChatFastPro_workflowExcluded() {
        assertThat(L1Compressor.compressionPointActive(properties, "task", "fast")).isTrue();
        assertThat(L1Compressor.compressionPointActive(properties, "task", "pro")).isTrue();
        assertThat(L1Compressor.compressionPointActive(properties, "task", "workflow")).isFalse();
        assertThat(L1Compressor.compressionPointActive(properties, "task", null)).isTrue();
        // chat 二期跟随（§5.5.4 ④ 落地分期）：fast/pro 启用、workflow 退出
        assertThat(L1Compressor.compressionPointActive(properties, "chat", "fast")).isTrue();
        assertThat(L1Compressor.compressionPointActive(properties, "chat", "pro")).isTrue();
        assertThat(L1Compressor.compressionPointActive(properties, "chat", "workflow")).isFalse();
        assertThat(L1Compressor.compressionPointActive(properties, "chat", null)).isTrue();
        properties.setEnabled(false);
        assertThat(L1Compressor.compressionPointActive(properties, "task", "fast")).isFalse();
        assertThat(L1Compressor.compressionPointActive(properties, "chat", "fast")).isFalse();
        properties.setEnabled(true);
        properties.getL1().getCompressionPoint().setEnabled(false);
        assertThat(L1Compressor.compressionPointActive(properties, "task", "fast")).isFalse();
        assertThat(L1Compressor.compressionPointActive(properties, "chat", "fast")).isFalse();
    }

    @Test
    void partitionByPoint_foldedFar_summarizedMid_restNear() {
        List<SessionTurn> history = rounds(4);
        L1Compressor.WindowBands bands = L1Compressor.partitionByPoint(
                history, Set.of("u0", "a0"), Set.of("a2"));

        assertThat(bands.far()).extracting(SessionTurn::messageId).containsExactly("u0", "a0");
        assertThat(bands.mid()).extracting(SessionTurn::messageId).containsExactly("u2", "a2");
        assertThat(bands.near()).extracting(SessionTurn::messageId)
                .containsExactly("u1", "a1", "u3", "a3");
    }

    @Test
    void roundFullyFolded_requiresAllIdsPresent() {
        List<SessionTurn> round = List.of(
                SessionTurn.of("u0", "user", "q"),
                SessionTurn.of("a0", "assistant", "a"));
        assertThat(L1Compressor.roundFullyFolded(round, Set.of("u0", "a0"))).isTrue();
        assertThat(L1Compressor.roundFullyFolded(round, Set.of("u0"))).isFalse();
        assertThat(L1Compressor.roundFullyFolded(round, Set.of())).isFalse();
        assertThat(L1Compressor.roundFullyFolded(
                List.of(SessionTurn.of("user", "no id")), Set.of("u0"))).isFalse();
    }

    @Test
    void compress_taskFast_reorganizesNearMidFar() {
        when(conversationRepo.findById("c1")).thenReturn(Optional.of(taskConv("fast")));
        when(llm.complete(eq("far-system"), anyString())).thenReturn("折叠远窗");

        // 6 轮 > turnBackstop(4) 触发；nearKeep=2 midKeep=2 → fold r0+r1, mid r2+r3, near r4+r5
        compressor.compress("u", "default", "c1", rounds(6));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> midCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> farCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> foldedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), farCaptor.capture(), foldedCaptor.capture(),
                anyCollection(), eq(2), eq(2));
        assertThat(midCaptor.getValue()).containsOnlyKeys("a2", "a3");
        assertThat(farCaptor.getValue()).isEqualTo("折叠远窗");
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder("u0", "a0", "u1", "a1");
    }

    @Test
    void compress_taskFast_alreadyFoldedRoundsExcludedFromLive() {
        when(conversationRepo.findById("c1")).thenReturn(Optional.of(taskConv("fast")));
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        entity.setFarSummary("旧远窗");
        entity.setFarFoldedMsgIds("[\"u0\",\"a0\"]");
        when(store.find("c1")).thenReturn(Optional.of(entity));
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of("u0", "a0"));
        when(store.farSummaryOf(any())).thenReturn("旧远窗");
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("摘要");

        // 5 轮；r0 已折叠 → 活跃 4 轮 > 4 不成立？turnBackstop=4 → 4>4 false，改 6 轮
        compressor.compress("u", "default", "c1", rounds(6));

        // 活跃 5 轮（r1..r5）> turnBackstop(4) 触发：fold r1, mid r2+r3（nearKeep=2 midKeep=2）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> midCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> foldedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), anyString(), foldedCaptor.capture(), anyCollection(), eq(2), eq(2));
        assertThat(midCaptor.getValue()).containsOnlyKeys("a2", "a3");
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder("u0", "a0", "u1", "a1");
        // r0 已折叠不重复送折叠：仅 r1 进入新折叠批次
        verify(llm).complete(eq("far-system"), anyString());
    }

    @Test
    void compress_taskWorkflow_fallsBackToSlidingWindow() {
        // workflow 退出压缩点：走滑动窗基线（3 轮未达宽限 → 不写库）
        when(conversationRepo.findById("c1")).thenReturn(Optional.of(taskConv("workflow")));

        compressor.compress("u", "default", "c1", rounds(3));

        verify(store, never()).upsert(
                anyString(), anyString(), anyString(), anyMap(), anyString(),
                anyCollection(), anyCollection(), anyInt(), anyInt());
    }

    @Test
    void compress_chatFast_usesCompressionPoint_4plus4() {
        // chat 二期（§5.5.7 差异表）：走压缩点模式，Near 4 轮原文 + Mid 4 轮摘要（LLM 结论语义）+ 其余折叠
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId("c1");
        conv.setKind("chat");
        conv.setExecutionPreference("fast");
        when(conversationRepo.findById("c1")).thenReturn(Optional.of(conv));
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("语义摘要");

        // 10 轮 > turnBackstop(4) 触发；nearKeep=4（chat）midKeep=4（chat）→ fold r0+r1, mid r2..r5, near r6..r9
        compressor.compress("u", "default", "c1", rounds(10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> midCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> foldedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), anyString(), foldedCaptor.capture(), anyCollection(), eq(4), eq(4));
        assertThat(midCaptor.getValue()).containsOnlyKeys("a2", "a3", "a4", "a5");
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder("u0", "a0", "u1", "a1");
        // chat Mid 走 LLM 语义摘要（task 为机械短结论，零 LLM）；4 条 assistant 各调一次
        verify(llm, times(4)).complete(eq("mid-system"), anyString());
    }

    @Test
    void advanceCompressionPoint_retiresOldestLive_summarizedUnchanged() {
        // §5.5 ①：同步推进零 LLM——P 前移退役最旧活跃轮，S（已折叠子集）不动
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        when(store.find("c1")).thenReturn(Optional.of(entity));
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of("u0", "a0"));
        when(store.parseFarSummarizedMsgIds(any(), any())).thenReturn(Set.of("u0", "a0"));
        when(store.parseMidAnswers(any())).thenReturn(Map.of("a1", "S1"));
        when(store.farSummaryOf(any())).thenReturn("旧远窗");

        // 6 轮；r0 已在 P → 活跃 5 轮；nearKeep=2 → 退役 r1+r2+r3，保留 r4+r5
        Set<String> advanced = compressor.advanceCompressionPoint(
                "u", "default", "c1", "task", rounds(6));

        assertThat(advanced).containsExactlyInAnyOrder(
                "u0", "a0", "u1", "a1", "u2", "a2", "u3", "a3");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> foldedCaptor = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> summarizedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                eq(Map.of("a1", "S1")), eq("旧远窗"),
                foldedCaptor.capture(), summarizedCaptor.capture(), eq(2), eq(2));
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder(
                "u0", "a0", "u1", "a1", "u2", "a2", "u3", "a3");
        // S 不动：退役轮留在 P\S 差集，写路径异步补折叠（防信息丢失）
        assertThat(summarizedCaptor.getValue()).containsExactlyInAnyOrder("u0", "a0");
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void advanceCompressionPoint_withinNearKeep_returnsOriginalNoUpsert() {
        when(store.find("c1")).thenReturn(Optional.empty());
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of());

        // 活跃 2 轮 ≤ nearKeep(2) → 无需推进，返回原集合并零写库
        Set<String> result = compressor.advanceCompressionPoint(
                "u", "default", "c1", "task", rounds(2));

        assertThat(result).isEmpty();
        verify(store, never()).upsert(
                anyString(), anyString(), anyString(), anyMap(), anyString(),
                anyCollection(), anyCollection(), anyInt(), anyInt());
    }

    @Test
    void advanceCompressionPoint_chat_retains4Rounds() {
        // chat 二期：同步推进按 kind 分化，nearKeep=4（chat）保底
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        when(store.find("c1")).thenReturn(Optional.of(entity));
        when(store.parseFarFoldedMsgIds(any())).thenReturn(Set.of());
        when(store.parseFarSummarizedMsgIds(any(), any())).thenReturn(Set.of());

        // 7 轮；活跃 7 轮 > nearKeep(4) → 退役 r0+r1+r2，保留 r3..r6
        Set<String> advanced = compressor.advanceCompressionPoint(
                "u", "default", "c1", "chat", rounds(7));

        assertThat(advanced).containsExactlyInAnyOrder(
                "u0", "a0", "u1", "a1", "u2", "a2");
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                anyMap(), anyString(), anyCollection(), anyCollection(), eq(4), eq(4));
    }

    @Test
    void compress_chatFast_overBudget_neverFoldsNear() {
        // chat 无硬预算（§5.5.7 差异表）：token 严重超预算也不激进折叠 Near，Near 保底 4 轮
        when(tokenEstimator.count(anyString())).thenReturn(5000);
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId("c1");
        conv.setKind("chat");
        conv.setExecutionPreference("fast");
        when(conversationRepo.findById("c1")).thenReturn(Optional.of(conv));
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("语义摘要");

        compressor.compress("u", "default", "c1", rounds(10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> foldedCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                anyMap(), anyString(), foldedCaptor.capture(), anyCollection(), eq(4), eq(4));
        // 仅 r0+r1 折叠；Near 4 轮（r6..r9）不被预算突破
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder("u0", "a0", "u1", "a1");
    }

    @Test
    void compress_taskFast_overBudget_foldsIntoNearFloor() {
        // task 有 ≤10k 硬预算：同超预算场景 Near 被激进折叠（保底 1 轮），与 chat 对照差异
        when(tokenEstimator.count(anyString())).thenReturn(5000);
        when(conversationRepo.findById("c1")).thenReturn(Optional.of(taskConv("fast")));
        when(llm.complete(eq("mid-system"), anyString())).thenReturn("摘要");

        compressor.compress("u", "default", "c1", rounds(10));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> foldedCaptor = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> midCaptor = ArgumentCaptor.forClass(Map.class);
        verify(store).upsert(
                eq("u"), eq("default"), eq("c1"),
                midCaptor.capture(), anyString(), foldedCaptor.capture(), anyCollection(), eq(2), eq(2));
        // Near 被折叠至仅剩最后 1 轮（r9），r0..r8 全部进折叠；Mid 因超预算也被全部退役
        assertThat(foldedCaptor.getValue()).containsExactlyInAnyOrder(
                "u0", "a0", "u1", "a1", "u2", "a2", "u3", "a3",
                "u4", "a4", "u5", "a5", "u6", "a6", "u7", "a7", "u8", "a8");
        assertThat(midCaptor.getValue()).isEmpty();
    }

    @Test
    void enforcePostCompactBudget_downgradesMidThenNear_keepsLastRound() {
        // §5.5 ⑮：超硬预算先退最旧 Mid、再退最旧 Near，保底 1 轮
        ContextProperties.L1 l1 = new ContextProperties.L1();
        l1.getCompressionPoint().setTaskPostCompactBudget(50);
        List<List<SessionTurn>> live = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            live.add(List.of(
                    SessionTurn.of("u" + i, "user", "x".repeat(400)),
                    SessionTurn.of("a" + i, "assistant", "y".repeat(400))));
        }
        TokenEstimator estimator = new TokenEstimator();

        int[] r = L1Compressor.enforcePostCompactBudget(
                live, 0, 2, l1, "", estimator, Map.of());

        // Mid 全部退役（midStart 推到 nearStart）且 Near 收到只剩最后一轮
        assertThat(r[0]).isEqualTo(r[1]);
        assertThat(r[1]).isEqualTo(live.size() - 1);
    }

    @Test
    void enforcePostCompactBudget_withinBudgetOrDisabled_returnsUnchanged() {
        ContextProperties.L1 l1 = new ContextProperties.L1();
        l1.getCompressionPoint().setTaskPostCompactBudget(1_000_000);
        List<List<SessionTurn>> live = List.of(
                List.of(SessionTurn.of("u0", "user", "q"), SessionTurn.of("a0", "assistant", "a")),
                List.of(SessionTurn.of("u1", "user", "q"), SessionTurn.of("a1", "assistant", "a")));
        TokenEstimator estimator = new TokenEstimator();

        assertThat(L1Compressor.enforcePostCompactBudget(
                live, 0, 1, l1, "", estimator, Map.of())).containsExactly(0, 1);
        l1.getCompressionPoint().setTaskPostCompactBudget(0);
        assertThat(L1Compressor.enforcePostCompactBudget(
                live, 0, 1, l1, "", estimator, Map.of())).containsExactly(0, 1);
    }
}
