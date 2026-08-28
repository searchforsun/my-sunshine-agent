package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.context.l3.L3RecallService;
import com.sunshine.orchestrator.conversation.ChatTurn;
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
import java.util.Set;
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
    @Mock
    private com.sunshine.orchestrator.context.l1.L1Compressor l1Compressor;

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
                tokenEstimator, modelWindowCache, null, null, resolver, l1Compressor);
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
                "u1", "default", "c1", history, "current query", null, "chat", null, "workflow"));

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
                "u1", "default", "c1", history, "q", null, "chat", null, "workflow"));

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
    void assemble_taskKindWorkflowMode_exitsContextInjection() {
        // task-scene §2.2：task×workflow 退出统一上下文链路——不注入 KV workspace / P0 guide。
        lenient().when(l2StateStore.assembleWorkspaceBlock("ws-1", "default"))
                .thenReturn("[workspace L2] plan/step: 完成");

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q", null, "task", "ws-1", "workflow"));

        assertThat(ctx.l2SystemBlock()).isBlank();
        assertThat(ctx.projectGuideBlock()).isBlank();
        verify(l2StateStore, never()).assembleWorkspaceBlock(anyString(), anyString());
        verify(l2StateStore, never()).assembleSystemBlock(anyString(), anyString());
    }

    @Test
    void assemble_taskKindFastMode_stillReadsWorkspaceScope() {
        when(l2StateStore.assembleWorkspaceBlock("ws-1", "default"))
                .thenReturn("[workspace L2] plan/step: 完成");

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q", null, "task", "ws-1", "fast"));

        assertThat(ctx.l2SystemBlock()).isEqualTo("[workspace L2] plan/step: 完成");
        verify(l2StateStore).assembleWorkspaceBlock("ws-1", "default");
    }

    @Test
    void assemble_chatKindWorkflowMode_notAffectedByTaskGate() {
        // workflow 裁剪仅作用于 task；chat×workflow 仍读 user scope。
        when(l2StateStore.assembleSystemBlock("u1", "default"))
                .thenReturn("[user L2] preference/style: 简洁");

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q", null, "chat", null, "workflow"));

        assertThat(ctx.l2SystemBlock()).isEqualTo("[user L2] preference/style: 简洁");
    }

    @Test
    void assemble_taskFast_compressionPoint_nearAfterPoint_neverTruncated() {
        // §5.5：task×fast 压缩点模式——Near 起点 = far_folded_msg_ids 之后；
        // 小预算也不从 Near 头部丢轮次（溢出走压缩，不裁剪）。
        properties.getL1().setNearTurns(1);
        int window = (int) Math.ceil(tokenEstimator.count("bbbb") / 0.8);
        lenient().when(modelWindowCache.windowFor(any())).thenReturn(window);
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        entity.setFarFoldedMsgIds("[\"u0\",\"a0\"]");
        when(l1Store.find("c1")).thenReturn(Optional.of(entity));
        when(l1Store.parseFarFoldedMsgIds(any())).thenReturn(Set.of("u0", "a0"));
        // 仅 r1 活跃且 nearKeep≥1 → 无需推进，返回原压缩点（生产契约：单调只增）
        when(l1Compressor.advanceCompressionPoint(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Set.of("u0", "a0"));
        when(l2StateStore.assembleWorkspaceBlock("ws-1", "default")).thenReturn("[ws L2]");
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "aaaa"),
                SessionTurn.of("a0", "assistant", "bbbb"),
                SessionTurn.of("u1", "user", "cc"),
                SessionTurn.of("a1", "assistant", "dddd"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q", null, "task", "ws-1", "fast"));

        // Near = 压缩点后全部原文（u1/a1），即使单条超预算也不丢头部轮次
        assertThat(ctx.nearTurns()).extracting(ChatTurn::content).containsExactly("cc", "dddd");
    }

    @Test
    void assemble_taskFast_overBudget_advancesPointAndRepartitions() {
        // §5.5 ①：L1 组装超预算 → 同步推进 P（零 LLM）→ 本轮按新 P 重组（Near 收缩）
        properties.getL1().setNearTurns(1);
        // 窗口压到 20 → budget = 16，长正文即超
        lenient().when(modelWindowCache.windowFor(any())).thenReturn(20);
        ConversationContextL1Entity entity = new ConversationContextL1Entity();
        entity.setConvId("c1");
        when(l1Store.find("c1")).thenReturn(Optional.of(entity));
        when(l1Store.parseFarFoldedMsgIds(any())).thenReturn(Set.of());
        when(l2StateStore.assembleWorkspaceBlock("ws-1", "default")).thenReturn("");
        // 推进后压缩点覆盖 r0+r1（单调：空集超集）
        when(l1Compressor.advanceCompressionPoint(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Set.of("u0", "a0", "u1", "a1"));
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "first round question body is long enough"),
                SessionTurn.of("a0", "assistant", "first round answer body is long enough"),
                SessionTurn.of("u1", "user", "second round question body is long enough"),
                SessionTurn.of("a1", "assistant", "second round answer body is long enough"),
                SessionTurn.of("u2", "user", "near question body"),
                SessionTurn.of("a2", "assistant", "near answer body"),
                SessionTurn.of("u3", "user", "latest question body"),
                SessionTurn.of("a3", "assistant", "latest answer body"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q", null, "task", "ws-1", "fast"));

        // 按新 P 重组：r0/r1 进远窗边界，Near 仅剩 r2/r3 原文
        assertThat(ctx.nearTurns()).extracting(ChatTurn::content)
                .containsExactly("near question body", "near answer body",
                        "latest question body", "latest answer body");
    }

    @Test
    void assemble_taskWorkflow_compressionPointDisabled_keepsSlidingWindow() {
        // workflow 退出压缩点：继续滑动窗基线（nearTurns=1 → 仅最后一轮）
        properties.getL1().setNearTurns(1);
        properties.getL1().setMidTurns(1);
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "q0"),
                SessionTurn.of("a0", "assistant", "a0"),
                SessionTurn.of("u1", "user", "q1"),
                SessionTurn.of("a1", "assistant", "a1"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q", null, "task", "ws-1", "workflow"));

        assertThat(ctx.nearTurns()).extracting(ChatTurn::content).containsExactly("q1", "a1");
        assertThat(ctx.midTurns()).extracting(ChatTurn::content).containsExactly("q0", "a0");
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

    @Test
    void toChatTurns_appendsDeterministicSchemaLines() {
        // ⑲ chat Near：工具轮 assistant 追加确定性 schema 行，user 轮原样
        SessionTurn user = SessionTurn.of("u0", "user", "查一下报销");
        SessionTurn toolAssistant = SessionTurn.of("a0", "assistant", "已查到。",
                List.of("[search_knowledge] keyArgs=query=报销流程 status=ok · result=命中 3 条"));

        List<ChatTurn> turns = ContextAssembler.toChatTurns(List.of(user, toolAssistant));

        assertThat(turns).extracting(ChatTurn::content).containsExactly(
                "查一下报销",
                "已查到。\n[search_knowledge] keyArgs=query=报销流程 status=ok · result=命中 3 条");
    }

    @Test
    void projectMid_appendsSchemaAfterSummary() {
        // ⑫/⑭ Mid：assistant 摘要后追加原样 schema 行（确定性，未经 LLM 改写）
        SessionTurn toolAssistant = SessionTurn.of("a0", "assistant", "原始正文",
                List.of("[sandbox__exec] keyArgs=amount=3000 status=ok exit=0 · result=ok"));
        SessionTurn plainUser = SessionTurn.of("u0", "user", "原文");

        List<ChatTurn> turns = ContextAssembler.projectMid(
                List.of(plainUser, toolAssistant), Map.of("a0", "两句话摘要。"));

        assertThat(turns).extracting(ChatTurn::content).containsExactly(
                "原文",
                "两句话摘要。\n[sandbox__exec] keyArgs=amount=3000 status=ok exit=0 · result=ok");
    }

    @Test
    void toChatTurns_taskNear_rendersFullProcess() {
        // task-scene §6.6：task Near assistant 轮渲染完整过程（think + tool 序列原文），user 轮原样
        SessionTurn user = SessionTurn.of("u0", "user", "报销单 3000 元审批");
        SessionTurn taskAssistant = new SessionTurn("a0", "assistant", "审批通过。",
                List.of("[sandbox__exec] keyArgs=cmd=pytest status=ok exit=0 · result=ok"),
                List.of(
                        "think: 先查报销单，再核对金额是否超限。",
                        "[sandbox__exec] keyArgs=cmd=pytest status=ok exit=0 · result=ok",
                        "[sandbox__edit] keyArgs=path=/workspace/a.py status=ok · result=--- a/a.py\n+++ b/a.py\n+print(2)"));

        List<ChatTurn> turns = ContextAssembler.toChatTurns(List.of(user, taskAssistant));

        assertThat(turns).extracting(ChatTurn::content).containsExactly(
                "报销单 3000 元审批",
                "think: 先查报销单，再核对金额是否超限。\n"
                        + "[sandbox__exec] keyArgs=cmd=pytest status=ok exit=0 · result=ok\n"
                        + "[sandbox__edit] keyArgs=path=/workspace/a.py status=ok"
                        + " · result=--- a/a.py\n+++ b/a.py\n+print(2)\n"
                        + "审批通过。");
    }

    @Test
    void toChatTurns_chatNear_processNull_keepsSchemaBehavior() {
        // chat（无完整过程）：processLines 为 null 时保持「正文 + schema 行」既有行为
        SessionTurn chatAssistant = SessionTurn.of("a0", "assistant", "已查到。",
                List.of("[search_knowledge] keyArgs=query=报销流程 status=ok · result=命中 3 条"));

        List<ChatTurn> turns = ContextAssembler.toChatTurns(List.of(chatAssistant));

        assertThat(turns).extracting(ChatTurn::content).containsExactly(
                "已查到。\n[search_knowledge] keyArgs=query=报销流程 status=ok · result=命中 3 条");
    }

    @Test
    void assemble_deferL3_skipsRecallButMountsAnchor() {
        // M0（authority §2.2 方案 A）：deferL3=true 时 assemble 不召回 L3，但挂分区锚点供路由后 attachL3
        List<SessionTurn> history = List.of(
                SessionTurn.of("u0", "user", "q0"),
                SessionTurn.of("a0", "assistant", "a0"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q", null, "chat", null, "fast", true));

        assertThat(ctx.l3MaterialBlock()).isEmpty();
        assertThat(ctx.l3Anchor()).isNotNull();
        assertThat(ctx.l3Anchor().excludeMsgIds()).contains("u0", "a0");
        verify(l3RecallService, never()).recall(anyString(), anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void assemble_nonDefer_keepsLegacyL3Behavior() {
        // deferL3 缺省（false）：L3 随 assemble 召回，anchor 为空
        List<SessionTurn> history = List.of(SessionTurn.of("u0", "user", "q0"));
        when(l3RecallService.recall(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn("[历史材料] 早期报销记录");

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q", null));

        assertThat(ctx.l3MaterialBlock()).isEqualTo("[历史材料] 早期报销记录");
        assertThat(ctx.l3Anchor()).isEqualTo(AssembledContext.L3Anchor.EMPTY);
    }

    @Test
    void attachL3_withAnchor_recallsAndInjects() {
        // 路由后按锚点召回：排除 Near/Mid 已覆盖消息，注入 L3 材料块
        AssembledContext base = AssembledContext.empty()
                .withL3Anchor(new AssembledContext.L3Anchor(Set.of("u0"), Set.of("far-1"), true));
        when(l3RecallService.recall("u1", "default", "q", Set.of("u0"), Set.of("far-1"), true))
                .thenReturn("[历史材料] 报销规则摘要");

        AssembledContext ctx = assembler.attachL3(base,
                new ContextAssembler.AssembleRequest("u1", "default", "c1", List.of(), "q", null));

        assertThat(ctx.l3MaterialBlock()).isEqualTo("[历史材料] 报销规则摘要");
    }

    @Test
    void attachL3_withoutAnchor_returnsAsIs() {
        // 无锚点（task / 无 query / 未 defer）→ 不召回，原样返回
        AssembledContext base = AssembledContext.empty();

        AssembledContext ctx = assembler.attachL3(base,
                new ContextAssembler.AssembleRequest("u1", "default", "c1", List.of(), "q", null));

        assertThat(ctx).isSameAs(base);
        assertThat(ctx.l3MaterialBlock()).isEmpty();
        verify(l3RecallService, never()).recall(anyString(), anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void attachL3_alreadyHasL3_returnsAsIs() {
        // 已含 L3（非 defer 调用方误调）→ 防重复召回
        AssembledContext base = AssembledContext.empty()
                .withL3MaterialBlock("[历史材料] 已有")
                .withL3Anchor(new AssembledContext.L3Anchor(Set.of("u0"), Set.of(), false));

        AssembledContext ctx = assembler.attachL3(base,
                new ContextAssembler.AssembleRequest("u1", "default", "c1", List.of(), "q", null));

        assertThat(ctx.l3MaterialBlock()).isEqualTo("[历史材料] 已有");
        verify(l3RecallService, never()).recall(anyString(), anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void attachL3_overBudget_dropsL3() {
        // 底座已用尽预算：L3 超剩余预算丢弃（L3 是最先让位的动态尾段）
        properties.getL1().setMaxTokensRatio(0.00005); // 预算 ~12 tokens → L3 放不下
        AssembledContext base = AssembledContext.empty()
                .withL3Anchor(new AssembledContext.L3Anchor(Set.of("u0"), Set.of(), false));
        when(l3RecallService.recall(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenReturn("[历史材料] 一段足够长的 L3 摘要内容，超预算时应被丢弃");

        AssembledContext ctx = assembler.attachL3(base,
                new ContextAssembler.AssembleRequest("u1", "default", "c1", List.of(), "q", null));

        assertThat(ctx.l3MaterialBlock()).isEmpty();
    }

    @Test
    void attachL3_recallFailure_returnsAsIs() {
        AssembledContext base = AssembledContext.empty()
                .withL3Anchor(new AssembledContext.L3Anchor(Set.of("u0"), Set.of(), false));
        when(l3RecallService.recall(anyString(), anyString(), anyString(), any(), any(), anyBoolean()))
                .thenThrow(new RuntimeException("rag down"));

        AssembledContext ctx = assembler.attachL3(base,
                new ContextAssembler.AssembleRequest("u1", "default", "c1", List.of(), "q", null));

        assertThat(ctx).isSameAs(base);
    }
}
