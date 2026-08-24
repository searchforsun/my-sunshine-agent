package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.context.l3.L3RecallService;
import com.sunshine.orchestrator.registry.ModelCapabilities;
import com.sunshine.orchestrator.registry.ModelCatalogDefinition;
import com.sunshine.orchestrator.registry.ModelCatalogScene;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextAssemblerTest {

    @Mock
    private ConversationContextL1Store l1Store;
    @Mock
    private L2StateStore l2StateStore;
    @Mock
    private L3RecallService l3RecallService;
    @Mock
    private ModelWindowCache modelWindowCache;

    private final TokenEstimator tokenEstimator = new TokenEstimator();
    private ContextProperties properties;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
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
        assembler = new ContextAssembler(properties, l1Store, l2StateStore, l3RecallService,
                tokenEstimator, modelWindowCache, null, null, resolver);
        lenient().when(modelWindowCache.windowFor(any())).thenReturn(256000);
        lenient().when(l1Store.find(anyString())).thenReturn(Optional.empty());
        lenient().when(l1Store.parseMidAnswers(any())).thenReturn(Map.of());
        lenient().when(l1Store.farSummaryOf(any())).thenReturn("");
        lenient().when(l2StateStore.assembleSystemBlock(anyString(), anyString())).thenReturn("");
        lenient().when(l3RecallService.recall(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn("");
    }

    @Test
    void assemble_keepsLastNearTurns() {
        properties.getL1().setNearTurns(2);
        properties.getL1().setMidTurns(0);
        List<SessionTurn> history = IntStream.range(0, 20)
                .mapToObj(i -> SessionTurn.of("m" + i, i % 2 == 0 ? "user" : "assistant", "m" + i))
                .toList();

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "current query"));

        assertThat(ctx.nearTurns()).hasSize(4);
        assertThat(ctx.nearTurns().get(0).content()).isEqualTo("m16");
        assertThat(ctx.nearTurns().get(1).content()).isEqualTo("m17");
        assertThat(ctx.nearTurns().get(2).content()).isEqualTo("m18");
        assertThat(ctx.nearTurns().get(3).content()).isEqualTo("m19");
        assertThat(ctx.midTurns()).isEmpty();
        assertThat(ctx.l2SystemBlock()).isBlank();
        assertThat(ctx.farSummaryBlock()).isBlank();
        assertThat(ctx.l3MaterialBlock()).isBlank();
    }

    @Test
    void assemble_dropsWholeTurnsFromHeadWhenOverBudget() {
        properties.getL1().setNearTurns(10);
        properties.getL1().setMidTurns(0);
        // 小窗口触发 token 裁剪：budget = window × 0.8，装不下 3 条则从头丢
        int bbbb = tokenEstimator.count("bbbb");
        int cc = tokenEstimator.count("cc");
        // window 使 budgetTokens = bbbb + cc（只装下后两条）
        int window = (int) Math.ceil((bbbb + cc) / 0.8);
        lenient().when(modelWindowCache.windowFor(any())).thenReturn(window);
        List<SessionTurn> history = List.of(
                SessionTurn.of("user", "aaaa"),
                SessionTurn.of("assistant", "bbbb"),
                SessionTurn.of("user", "cc"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q"));

        assertThat(ctx.nearTurns()).hasSize(2);
        assertThat(ctx.nearTurns().get(0).content()).isEqualTo("bbbb");
        assertThat(ctx.nearTurns().get(1).content()).isEqualTo("cc");
    }

    @Test
    void assemble_doesNotTruncateSingleMessageContent() {
        properties.getL1().setNearTurns(4);
        String longReply = "x".repeat(2000);
        List<SessionTurn> history = List.of(
                SessionTurn.of("user", "q"),
                SessionTurn.of("assistant", longReply));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "follow up"));

        assertThat(ctx.nearTurns().get(1).content()).hasSize(2000);
    }

    @Test
    void assemble_filtersBlankTurns() {
        properties.getL1().setNearTurns(8);
        List<SessionTurn> history = List.of(
                SessionTurn.of("user", "hello"),
                SessionTurn.of("assistant", "  "));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "again"));

        assertThat(ctx.nearTurns()).hasSize(1);
        assertThat(ctx.nearTurns().get(0).role()).isEqualTo("user");
    }

    @Test
    void assemble_whenDisabled_returnsEmpty() {
        properties.setEnabled(false);
        List<SessionTurn> history = List.of(SessionTurn.of("user", "hi"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q"));

        assertThat(ctx.hasAnyLayer()).isFalse();
    }

    @Test
    void assemble_emptyHistory_returnsEmptyNear() {
        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q"));

        assertThat(ctx.nearTurns()).isEmpty();
        assertThat(ctx.midTurns()).isEmpty();
        assertThat(ctx.l2SystemBlock()).isBlank();
    }

    @Test
    void assemble_nullHistory_returnsEmpty() {
        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", null, "q"));

        assertThat(ctx.hasAnyLayer()).isFalse();
    }

    @Test
    void assemble_historyWithinBudget_keepsAll() {
        properties.getL1().setNearTurns(8);
        List<SessionTurn> history = new ArrayList<>();
        history.add(SessionTurn.of("user", "写 cpp 快排"));
        history.add(SessionTurn.of("assistant", "cpp code full content"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "写 py 快排"));

        assertThat(ctx.nearTurns()).hasSize(2);
        assertThat(ctx.nearTurns().get(0).content()).isEqualTo("写 cpp 快排");
        assertThat(ctx.nearTurns().get(1).content()).isEqualTo("cpp code full content");
    }

    @Test
    void assemble_taskKind_readsWorkspaceScope() {
        when(l2StateStore.assembleWorkspaceBlock("ws-1", "default"))
                .thenReturn("[workspace L2] plan/step: 完成");

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q", null, "task", "ws-1"));

        assertThat(ctx.l2SystemBlock()).isEqualTo("[workspace L2] plan/step: 完成");
        verify(l2StateStore).assembleWorkspaceBlock("ws-1", "default");
        verify(l2StateStore, never()).assembleSystemBlock(anyString(), anyString());
    }

    @Test
    void assemble_taskKind_skipsAutomaticL3Recall() {
        // M3：task 会话不自动注入 L3（只写不自动注入），由 sunshine_session_search 按需恢复；
        // chat 会话保留自动召回。
        assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q", null, "task", "ws-1"));

        verify(l3RecallService, never()).recall(anyString(), anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void assemble_chatKind_keepsAutomaticL3Recall() {
        assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q", null, "chat", null));

        verify(l3RecallService).recall(anyString(), anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void assemble_chatKind_readsUserScope() {
        when(l2StateStore.assembleSystemBlock("u1", "default"))
                .thenReturn("[user L2] preference/style: 简洁");

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q", null, "chat", null));

        assertThat(ctx.l2SystemBlock()).isEqualTo("[user L2] preference/style: 简洁");
        verify(l2StateStore).assembleSystemBlock("u1", "default");
        verify(l2StateStore, never()).assembleWorkspaceBlock(anyString(), anyString());
    }

    @Test
    void assemble_legacyConstructorDefaultsToChatScope() {
        when(l2StateStore.assembleSystemBlock("u1", "default")).thenReturn("[user L2] legacy");

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q", null));

        assertThat(ctx.l2SystemBlock()).isEqualTo("[user L2] legacy");
        verify(l2StateStore).assembleSystemBlock("u1", "default");
        verify(l2StateStore, never()).assembleWorkspaceBlock(anyString(), anyString());
    }
}
