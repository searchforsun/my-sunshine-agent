package com.sunshine.orchestrator.context.admin;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.ModelWindowCache;
import com.sunshine.orchestrator.context.TokenEstimator;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.RebuildCheckView;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l2.L2ConflictMerger;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import com.sunshine.orchestrator.context.job.ContextMaintenanceService;
import com.sunshine.orchestrator.context.l3.HistoryRagClient;
import com.sunshine.orchestrator.context.l3.L3IngestService;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import com.sunshine.orchestrator.registry.ResolvedModelScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * O4 账本重建校验（{@link ContextAdminService#verifyRebuild}）：
 * 滑动窗/压缩点两模式下的结构不变量与判定分级（ERROR/WARN/PASS）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextAdminRebuildCheckTest {

    private static final String CONV = "conv-o4";

    @Mock
    private UserContextStateRepository l2Repository;
    @Mock
    private ConversationContextL1Repository l1Repository;
    @Mock
    private ChatConversationRepository conversationRepository;
    @Mock
    private ChatMessageRepository messageRepository;
    @Mock
    private L3IngestService l3IngestService;
    @Mock
    private HistoryRagClient historyRagClient;
    @Mock
    private ContextMaintenanceService maintenanceService;
    @Mock
    private ModelWindowCache modelWindowCache;
    @Mock
    private ModelSceneResolver modelSceneResolver;

    private ContextProperties properties;
    private ContextAdminService service;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        ConversationContextL1Store l1Store = new ConversationContextL1Store(l1Repository, properties);
        L2StateStore l2StateStore = new L2StateStore(l2Repository, new L2ConflictMerger(), properties, null);
        service = new ContextAdminService(
                l2Repository, l1Repository, l1Store, conversationRepository, messageRepository,
                l3IngestService, historyRagClient, maintenanceService, properties,
                new TokenEstimator(), modelWindowCache, l2StateStore, modelSceneResolver);
        when(modelSceneResolver.resolve(any(), any()))
                .thenReturn(new ResolvedModelScene("m", null, Map.of(), 100, 0, null, false));
        when(modelWindowCache.windowFor("m")).thenReturn(100);
    }

    private void givenConversation(String kind, String executionPreference) {
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId(CONV);
        conv.setUserId("u1");
        conv.setTenantId("default");
        conv.setKind(kind);
        conv.setExecutionPreference(executionPreference);
        when(conversationRepository.findById(CONV)).thenReturn(Optional.of(conv));
    }

    private void givenLedgerRounds(int rounds) {
        List<ChatMessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            messages.add(message("u" + i, "user", "问题" + i + " " + "x".repeat(80)));
            messages.add(message("a" + i, "assistant", "回答" + i + " " + "y".repeat(80)));
        }
        when(messageRepository.findByConversationIdOrderBySeqAsc(CONV)).thenReturn(messages);
    }

    private void givenL1View(ConversationContextL1Entity entity) {
        when(l1Repository.findById(CONV)).thenReturn(Optional.ofNullable(entity));
    }

    private static ChatMessageEntity message(String id, String role, String content) {
        ChatMessageEntity m = new ChatMessageEntity();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        m.setStatus("completed");
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        return m;
    }

    private static ConversationContextL1Entity entity(
            String midAnswersJson, String farSummary, String foldedJson, int nearN, int midN) {
        ConversationContextL1Entity e = new ConversationContextL1Entity();
        e.setConvId(CONV);
        e.setUserId("u1");
        e.setTenantId("default");
        e.setMidAnswers(midAnswersJson);
        e.setFarSummary(farSummary);
        e.setFarFoldedMsgIds(foldedJson);
        e.setNearN(nearN);
        e.setMidN(midN);
        e.setUpdatedAt(Instant.now());
        return e;
    }

    @Test
    void rebuild_slidingWindow_consistentView_pass() {
        // workflow 退出压缩点 → 滑动窗基线（chat×fast 已二期启用压缩点，见 point 用例）
        givenConversation("chat", "workflow");
        givenLedgerRounds(6);
        // near=2 mid=2 → far=r0,r1；折叠链与远窗区一致，中窗摘要键均在中窗
        givenL1View(entity("{\"a2\":\"S2\",\"a3\":\"S3\"}", "远窗摘要",
                "[\"u0\",\"a0\",\"u1\",\"a1\"]", 2, 2));

        RebuildCheckView view = service.verifyRebuild(CONV);

        assertThat(view.verdict()).isEqualTo("PASS");
        assertThat(view.errors()).isEmpty();
        assertThat(view.mode()).isEqualTo("sliding-window");
        assertThat(view.viewExists()).isTrue();
        assertThat(view.shouldCompress()).isTrue();
        assertThat(view.nearRounds()).isEqualTo(2);
        assertThat(view.midRounds()).isEqualTo(2);
        assertThat(view.farRounds()).isEqualTo(2);
        assertThat(view.summaryMatchRate()).isEqualTo(1.0);
    }

    @Test
    void rebuild_viewMissingButShouldCompress_error() {
        givenConversation("chat", "fast");
        givenLedgerRounds(6);
        givenL1View(null);

        RebuildCheckView view = service.verifyRebuild(CONV);

        assertThat(view.verdict()).isEqualTo("ERROR");
        assertThat(view.viewExists()).isFalse();
        assertThat(view.errors()).anyMatch(e -> e.startsWith("H1"));
    }

    @Test
    void rebuild_viewMissingAndBelowThreshold_pass() {
        givenConversation("chat", "fast");
        // 窗口放大到阈值之上 → 账本无需压缩，视图缺失不算内容丢失
        when(modelWindowCache.windowFor("m")).thenReturn(1_000_000);
        givenLedgerRounds(2);
        givenL1View(null);

        RebuildCheckView view = service.verifyRebuild(CONV);

        assertThat(view.verdict()).isEqualTo("PASS");
        assertThat(view.shouldCompress()).isFalse();
        assertThat(view.errors()).isEmpty();
    }

    @Test
    void rebuild_foldedWithoutFarSummary_error() {
        givenConversation("chat", "fast");
        givenLedgerRounds(6);
        givenL1View(entity("{\"a2\":\"S2\",\"a3\":\"S3\"}", "",
                "[\"u0\",\"a0\",\"u1\",\"a1\"]", 2, 2));

        RebuildCheckView view = service.verifyRebuild(CONV);

        assertThat(view.verdict()).isEqualTo("ERROR");
        assertThat(view.errors()).anyMatch(e -> e.startsWith("H4"));
    }

    @Test
    void rebuild_foldedIdOutsideFarBand_error() {
        givenConversation("chat", "fast");
        givenLedgerRounds(6);
        // a5 属近窗却被标记已折叠 → 分区失配
        givenL1View(entity("{\"a2\":\"S2\",\"a3\":\"S3\"}", "远窗摘要",
                "[\"u0\",\"a0\",\"a5\"]", 2, 2));

        RebuildCheckView view = service.verifyRebuild(CONV);

        assertThat(view.verdict()).isEqualTo("ERROR");
        assertThat(view.errors()).anyMatch(e -> e.startsWith("H3"));
    }

    @Test
    void rebuild_pointMode_prefixFolded_pass() {
        givenConversation("task", "fast");
        givenLedgerRounds(5);
        // 压缩点模式：折叠链 = 头部连续前缀 r0
        givenL1View(entity("{}", "远窗摘要", "[\"u0\",\"a0\"]", 2, 2));

        RebuildCheckView view = service.verifyRebuild(CONV);

        assertThat(view.verdict()).isEqualTo("PASS");
        assertThat(view.mode()).isEqualTo("compression-point");
        assertThat(view.errors()).isEmpty();
        assertThat(view.farRounds()).isEqualTo(1);
        assertThat(view.nearRounds()).isEqualTo(4);
    }

    @Test
    void rebuild_pointMode_interleavedFold_error() {
        givenConversation("task", "fast");
        givenLedgerRounds(5);
        // r0 未折叠而 r1 已折叠 → 前缀不变量破坏
        givenL1View(entity("{}", "远窗摘要", "[\"u1\",\"a1\"]", 2, 2));

        RebuildCheckView view = service.verifyRebuild(CONV);

        assertThat(view.verdict()).isEqualTo("ERROR");
        assertThat(view.errors()).anyMatch(e -> e.startsWith("H6"));
    }

    @Test
    void rebuild_midKeySlidOutOfMidWindow_warnButPass() {
        // workflow 退出压缩点 → 滑动窗 mid 分区（chat×fast 压缩点模式下按摘要键集分区，无滑出判定）
        givenConversation("chat", "workflow");
        givenLedgerRounds(6);
        // near=1 mid=1 → mid 仅 r4；a0 摘要已滑入远窗（WARN），且远窗未折叠（S3）
        givenL1View(entity("{\"a0\":\"S0\"}", "远窗摘要", "[]", 1, 1));

        RebuildCheckView view = service.verifyRebuild(CONV);

        assertThat(view.verdict()).isEqualTo("PASS");
        assertThat(view.errors()).isEmpty();
        assertThat(view.warnings()).anyMatch(w -> w.startsWith("S5"));
        assertThat(view.warnings()).anyMatch(w -> w.startsWith("S3"));
        assertThat(view.summaryMatchRate()).isLessThan(1.0);
    }
}
