package com.sunshine.orchestrator.context.admin;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.model.ModelSceneKey;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.ModelWindowCache;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.TokenEstimator;
import com.sunshine.orchestrator.context.admin.ContextAdminDtos.RebuildCheckView;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l1.L1Compressor;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.conversation.MessageBodyText;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 账本重建校验（只读，memory-ledger-view §6.2）：以 {@code chat_message} 为账本重放会话，
 * 用与 {@link L1Compressor} 同源的分区算法复算 Near/Mid/Far，对账 {@code conversation_context_l1} 视图。
 * Mid/Far 摘要为 LLM 输出，不重放重算，仅校验结构不变量：视图存在性、折叠链引用、压缩点前缀、中窗摘要引用。
 * 判定：ERROR=账本可重建但视图缺失/损坏；WARN=可解释漂移（异步滞后 / 政策漂移）；否则 PASS。
 */
@Component
@RequiredArgsConstructor
public class ContextRebuildVerifier {

    private final ConversationContextL1Repository l1Repository;
    private final ConversationContextL1Store l1Store;
    private final ChatConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ContextProperties contextProperties;
    private final TokenEstimator tokenEstimator;
    private final ModelWindowCache modelWindowCache;
    private final L2StateStore l2StateStore;
    private final ModelSceneResolver modelSceneResolver;

    public RebuildCheckView verifyRebuild(String convId) {
        requireText(convId, "convId");
        ChatConversationEntity conv = conversationRepository.findById(convId)
                .orElseThrow(() -> new BizException(CommonErrorCode.NOT_FOUND));
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<SessionTurn> history = rebuildLedgerHistory(convId, conv.getKind());
        List<List<SessionTurn>> rounds = L1Compressor.groupRounds(history);
        ConversationContextL1Entity entity = l1Repository.findById(convId).orElse(null);
        boolean viewExists = entity != null;
        Map<String, String> midAnswers = l1Store.parseMidAnswers(entity);
        LinkedHashSet<String> foldedIds = new LinkedHashSet<>(l1Store.parseFarFoldedMsgIds(entity));
        // P/S 分离（compression spec §5.5 ①）：summarized = 已实际折叠进 far_summary 的子集；
        // 差集 = 同步推进退役但尚未折叠的轮次（瞬态合法，写路径补折叠）
        Set<String> summarizedIds = entity != null
                ? l1Store.parseFarSummarizedMsgIds(entity, foldedIds)
                : Set.of();
        int gapCount = (int) rounds.stream()
                .filter(round -> !L1Compressor.roundFullyFolded(round, summarizedIds)
                        && L1Compressor.roundFullyFolded(round, foldedIds))
                .count();
        String farSummary = l1Store.farSummaryOf(entity);

        if (!contextProperties.isEnabled()) {
            warnings.add("context 总开关关闭，无视图可校验");
            return new RebuildCheckView(convId, conv.getKind(), "disabled", viewExists, false,
                    history.size(), rounds.size(), foldedIds.size(), midAnswers.size(),
                    farSummary.length(), 0, 0, 0, 0, 1.0, "PASS", List.of(), List.copyOf(warnings));
        }

        boolean pointMode = L1Compressor.compressionPointActive(
                contextProperties, conv.getKind(), conv.getExecutionPreference());
        ContextProperties.L1 l1 = contextProperties.getL1();
        String effectiveModel = modelSceneResolver
                .resolve(ModelSceneKey.CHAT.key(), null).effectiveModel();
        int modelWindow = modelWindowCache.windowFor(effectiveModel);

        boolean shouldCompress;
        L1Compressor.WindowBands bands;
        if (pointMode) {
            List<List<SessionTurn>> liveRounds = new ArrayList<>();
            for (List<SessionTurn> round : rounds) {
                if (!L1Compressor.roundFullyFolded(round, foldedIds)) {
                    liveRounds.add(round);
                }
            }
            String l2Block = rebuildL2Block(conv);
            shouldCompress = L1Compressor.shouldCompressAtPoint(
                    liveRounds, l1, modelWindow, tokenEstimator, l2Block, farSummary);
            bands = L1Compressor.partitionByPoint(history, foldedIds, midAnswers.keySet());
        } else {
            shouldCompress = L1Compressor.shouldCompress(history, l1, modelWindow, tokenEstimator);
            int nearN = entity != null && entity.getNearN() > 0
                    ? entity.getNearN()
                    : Math.max(1, l1.getNearTurns());
            int midN = entity != null && entity.getMidN() >= 0
                    ? entity.getMidN()
                    : Math.max(0, l1.getMidTurns());
            bands = L1Compressor.partition(history, nearN, midN);
        }
        int nearRounds = L1Compressor.groupRounds(bands.near()).size();
        int midRounds = L1Compressor.groupRounds(bands.mid()).size();
        int farRounds = L1Compressor.groupRounds(bands.far()).size();

        List<Boolean> results = new ArrayList<>();
        // H1：账本已达压缩条件 → 视图必须存在（删行/压缩失败 = 内容丢失）
        hard(results, viewExists || !shouldCompress, errors,
                "H1 账本需压缩但 L1 视图缺失（账本可重建而视图缺失）");
        // S1：滑动窗模式视图存在但当前账本已低于阈值 → 政策/窗口漂移；
        // 压缩点模式压缩后活跃轮回落低于阈值属正常，不检查。
        if (viewExists && !pointMode) {
            soft(results, shouldCompress, warnings,
                    "S1 L1 视图存在但当前账本低于压缩阈值（政策/窗口漂移）");
        }
        if (viewExists) {
            Set<String> ledgerIds = new HashSet<>();
            Map<String, String> roleById = new HashMap<>();
            for (SessionTurn t : history) {
                if (t != null && StringUtils.hasText(t.messageId())) {
                    ledgerIds.add(t.messageId());
                    roleById.put(t.messageId(), t.role());
                }
            }
            Set<String> farBandIds = bandMessageIds(bands.far());
            Set<String> midBandAssistantIds = bandAssistantIds(bands.mid());
            for (String fid : foldedIds) {
                if (!ledgerIds.contains(fid)) {
                    soft(results, false, warnings, "S2 折叠引用不在账本: " + fid);
                } else {
                    hard(results, farBandIds.contains(fid), errors,
                            "H3 折叠引用不在远窗区（分区失配）: " + fid);
                }
            }
            if (!foldedIds.isEmpty()) {
                // 有已折叠（summarized）轮次却无摘要 = 摘要丢失；
                // 仅有间隙轮（退役未折叠）时 far_summary 为空合法
                if (!summarizedIds.isEmpty()) {
                    hard(results, StringUtils.hasText(farSummary), errors,
                            "H4 折叠链非空但 far_summary 为空（摘要丢失）");
                }
                if (gapCount > 0) {
                    soft(results, false, warnings,
                            "S7 同步推进退役未折叠轮次：" + gapCount + " 轮（写路径异步补折叠）");
                }
            }
            if (!pointMode && !bands.far().isEmpty()) {
                boolean allFolded = true;
                for (SessionTurn t : bands.far()) {
                    if (t != null && StringUtils.hasText(t.messageId())
                            && !foldedIds.contains(t.messageId())) {
                        allFolded = false;
                        break;
                    }
                }
                soft(results, allFolded, warnings, "S3 远窗轮次未完全折叠（异步压缩收敛滞后）");
            }
            for (String midKey : midAnswers.keySet()) {
                String role = roleById.get(midKey);
                if (role == null) {
                    soft(results, false, warnings, "S4 中窗摘要引用不在账本: " + midKey);
                } else if (!"assistant".equals(role)) {
                    soft(results, false, warnings, "S4 中窗摘要引用非 assistant 消息: " + midKey);
                } else {
                    soft(results, midBandAssistantIds.contains(midKey), warnings,
                            "S5 中窗摘要已滑出中窗（窗口滑动，待异步压缩收敛）: " + midKey);
                }
            }
            for (SessionTurn t : bands.mid()) {
                if (t != null && "assistant".equals(t.role())
                        && StringUtils.hasText(t.messageId())
                        && !midAnswers.containsKey(t.messageId())) {
                    soft(results, false, warnings,
                            "S6 中窗 assistant 无摘要（压缩时 LLM 失败，读侧回退原文）: " + t.messageId());
                }
            }
        }
        // H6：压缩点前缀不变量——已折叠轮次必须占据账本头部连续前缀
        if (pointMode && !foldedIds.isEmpty()) {
            boolean prefix = true;
            boolean seenUnfolded = false;
            for (List<SessionTurn> round : rounds) {
                boolean folded = L1Compressor.roundFullyFolded(round, foldedIds);
                if (seenUnfolded && folded) {
                    prefix = false;
                    break;
                }
                if (!folded) {
                    seenUnfolded = true;
                }
            }
            hard(results, prefix, errors, "H6 压缩点前缀不变量破坏（折叠轮与活跃轮交错）");
        }

        int okCount = 0;
        for (Boolean b : results) {
            if (Boolean.TRUE.equals(b)) {
                okCount++;
            }
        }
        double rate = results.isEmpty() ? 1.0 : (double) okCount / results.size();
        String verdict = errors.isEmpty() ? "PASS" : "ERROR";
        return new RebuildCheckView(
                convId, conv.getKind(),
                pointMode ? "compression-point" : "sliding-window",
                viewExists, shouldCompress, history.size(), rounds.size(),
                foldedIds.size(), midAnswers.size(), farSummary.length(),
                nearRounds, midRounds, farRounds, gapCount, rate, verdict,
                List.copyOf(errors), List.copyOf(warnings));
    }

    /** 账本重放：与 {@code ContextWritePath} 写路径同过滤（非 streaming、user/assistant、正文非空）。 */
    private List<SessionTurn> rebuildLedgerHistory(String convId, String kind) {
        List<SessionTurn> history = new ArrayList<>();
        for (ChatMessageEntity m : messageRepository.findByConversationIdOrderBySeqAsc(convId)) {
            if (m == null) {
                continue;
            }
            if (!"user".equals(m.getRole()) && !"assistant".equals(m.getRole())) {
                continue;
            }
            if (MessageStatus.STREAMING.equals(m.getStatus())) {
                continue;
            }
            String body = MessageBodyText.resolve(m);
            if (!StringUtils.hasText(body)) {
                continue;
            }
            history.add(SessionTurn.fromMessage(m.getId(), m.getRole(), body, m.getSteps(), kind));
        }
        return List.copyOf(history);
    }

    /** 与 L1Compressor.resolveL2Block 同 scope 选择：task→workspace 块；其余→user 块。 */
    private String rebuildL2Block(ChatConversationEntity conv) {
        if ("task".equals(conv.getKind())) {
            return l2StateStore.assembleWorkspaceBlock(conv.getWorkspaceId(), conv.getTenantId());
        }
        return l2StateStore.assembleSystemBlock(conv.getUserId(), conv.getTenantId());
    }

    private static Set<String> bandMessageIds(List<SessionTurn> band) {
        Set<String> ids = new HashSet<>();
        if (band == null) {
            return ids;
        }
        for (SessionTurn t : band) {
            if (t != null && StringUtils.hasText(t.messageId())) {
                ids.add(t.messageId());
            }
        }
        return ids;
    }

    private static Set<String> bandAssistantIds(List<SessionTurn> band) {
        Set<String> ids = new HashSet<>();
        if (band == null) {
            return ids;
        }
        for (SessionTurn t : band) {
            if (t != null && "assistant".equals(t.role()) && StringUtils.hasText(t.messageId())) {
                ids.add(t.messageId());
            }
        }
        return ids;
    }

    private static void hard(List<Boolean> results, boolean ok, List<String> errors, String msg) {
        results.add(ok);
        if (!ok && msg != null) {
            errors.add(msg);
        }
    }

    private static void soft(List<Boolean> results, boolean ok, List<String> warnings, String msg) {
        results.add(ok);
        if (!ok && msg != null) {
            warnings.add(msg);
        }
    }

    private static void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(CommonErrorCode.BAD_REQUEST);
        }
    }
}
