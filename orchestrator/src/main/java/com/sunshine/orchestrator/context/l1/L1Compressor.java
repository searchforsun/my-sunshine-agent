package com.sunshine.orchestrator.context.l1;

import com.sunshine.common.model.ModelSceneKey;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.ModelWindowCache;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.TokenEstimator;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 Mid/Far 压缩：逼近字符预算或**问答轮次**超 near+mid 时，异步写 mid_answers / far_summary。
 * <p>一轮 = 一次 user 提问及其后的 assistant（等）回复；{@code near-turns}/{@code mid-turns} 按轮次计，非消息条数。
 * <p>同一 convId 的 compress 经 ConcurrentHashMap 锁串行，避免并发 upsert 丢更新。
 * Far 仅增量折叠：只把尚未记入 far_folded_msg_ids 的 Far 轮次送给 LLM；
 * 折叠时注入现行 L2，冲突以 L2 为准，避免 Far 摘要污染 system。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L1Compressor {

    public static final String MID_COMPRESS_PROMPT = "context.l1.mid-compress";
    public static final String FAR_FOLD_PROMPT = "context.l1.far-fold";

    private final ContextProperties contextProperties;
    private final LlmGatewayClient llmGatewayClient;
    private final ConversationContextL1Store l1Store;
    private final L2StateStore l2StateStore;
    private final PromptCatalogHolder catalogHolder;
    private final TokenEstimator tokenEstimator;
    private final ModelWindowCache modelWindowCache;
    private final com.sunshine.orchestrator.registry.ModelSceneResolver modelSceneResolver;
    private final ChatConversationRepository conversationRepo;

    /** 按会话串行化 compress，防止并发 async 丢更新。 */
    private final ConcurrentHashMap<String, Object> compressLocks = new ConcurrentHashMap<>();

    @Async
    public void compressAsync(String userId, String tenantId, String convId, List<SessionTurn> history) {
        try {
            compress(userId, tenantId, convId, history);
        } catch (Exception e) {
            log.warn("[ContextL1] 压缩失败 conv={}: {}", convId, e.getMessage());
        }
    }

    public void compress(String userId, String tenantId, String convId, List<SessionTurn> history) {
        if (!contextProperties.isEnabled() || history == null || history.isEmpty()) {
            return;
        }
        if (!StringUtils.hasText(convId)) {
            return;
        }
        Object lock = compressLocks.computeIfAbsent(convId, id -> new Object());
        synchronized (lock) {
            compressLocked(userId, tenantId, convId, history);
        }
    }

    private void compressLocked(String userId, String tenantId, String convId, List<SessionTurn> history) {
        ChatConversationEntity conv = findConversation(convId);
        if (compressionPointActive(conv)) {
            compressByCompressionPoint(userId, tenantId, convId, history, conv);
            return;
        }
        ContextProperties.L1 l1 = contextProperties.getL1();
        String effectiveModel = modelSceneResolver.resolve(ModelSceneKey.CHAT.key(), null).effectiveModel();
        int modelWindow = modelWindowCache.windowFor(effectiveModel);
        if (!shouldCompress(history, l1, modelWindow, tokenEstimator)) {
            return;
        }
        ConversationContextL1Entity existing = l1Store.find(convId).orElse(null);
        String farSummary = l1Store.farSummaryOf(existing);
        String l2Block = resolveL2Block(conv, userId, tenantId);
        // 自适应 Near 轮数：默认保轮数完整，组装估算超阈值时逐轮缩小（溢出转 Mid）
        int nearN = resolveNearRounds(history, l1, modelWindow, tokenEstimator, l2Block, farSummary);
        int midN = Math.max(0, l1.getMidTurns());
        WindowBands bands = partition(history, nearN, midN);
        Map<String, String> midAnswers = new HashMap<>(l1Store.parseMidAnswers(existing));
        LinkedHashSet<String> foldedIds = new LinkedHashSet<>(l1Store.parseFarFoldedMsgIds(existing));
        // 滑动窗模式折叠即摘要，两集合一致（存量兼容回退同为 folded）
        LinkedHashSet<String> summarizedIds = new LinkedHashSet<>(
                l1Store.parseFarSummarizedMsgIds(existing, foldedIds));

        Set<String> midAssistantIds = new HashSet<>();
        for (SessionTurn turn : bands.mid()) {
            if (!"assistant".equals(turn.role()) || !StringUtils.hasText(turn.messageId())) {
                continue;
            }
            midAssistantIds.add(turn.messageId());
            if (midAnswers.containsKey(turn.messageId())) {
                continue;
            }
            String summary = resolveMidAnswer(conv, turn.content());
            if (StringUtils.hasText(summary)) {
                midAnswers.put(turn.messageId(), summary.strip());
            }
        }
        // 只保留当前中窗 assistant，滑入近窗 / 远窗的旧摘要剔除
        midAnswers.keySet().removeIf(id -> !midAssistantIds.contains(id));

        List<SessionTurn> newToFold = new ArrayList<>();
        for (SessionTurn turn : bands.far()) {
            if (!StringUtils.hasText(turn.messageId())) {
                continue;
            }
            if (!foldedIds.contains(turn.messageId())) {
                newToFold.add(turn);
            }
        }
        if (!newToFold.isEmpty()) {
            String folded = foldFar(farSummary, newToFold, l2Block);
            if (StringUtils.hasText(folded)) {
                farSummary = folded.strip();
            }
            for (SessionTurn turn : newToFold) {
                foldedIds.add(turn.messageId());
                summarizedIds.add(turn.messageId());
            }
        }

        l1Store.upsert(userId, tenantId, convId, midAnswers, farSummary,
                foldedIds, summarizedIds, nearN, midN);
        log.debug("[ContextL1] compressed conv={} midKeys={} farLen={} folded={}",
                convId, midAnswers.size(), farSummary != null ? farSummary.length() : 0, foldedIds.size());
    }

    private ChatConversationEntity findConversation(String convId) {
        if (!StringUtils.hasText(convId)) {
            return null;
        }
        try {
            return conversationRepo.findById(convId).orElse(null);
        } catch (Exception e) {
            log.warn("[ContextL1] conversation 反查失败 conv={}: {}", convId, e.getMessage());
            return null;
        }
    }

    /**
     * 压缩点模式启用面（五层 §5.5 / task-scene §2.2 v8）：kind=task × (fast|pro) 一期；
     * chat × (fast|pro) 二期跟随（§5.5.4 ④ 落地分期，2026-08-26）；workflow 退出。
     * chat/task 机制同构，差异仅 Near/Mid 参数（chat 4+4+Far / task 2+2+Far≤10k）与
     * 静态块内容（§5.5.7 差异表），由调用方按 kind 选参数。
     */
    public static boolean compressionPointActive(ContextProperties props, String kind, String executionMode) {
        if (props == null || !props.isEnabled()) {
            return false;
        }
        if (!"task".equals(kind) && !"chat".equals(kind)) {
            return false;
        }
        ContextProperties.L1.CompressionPoint point = props.getL1().getCompressionPoint();
        if (point == null || !point.isEnabled()) {
            return false;
        }
        if (!StringUtils.hasText(executionMode)) {
            return true;
        }
        String mode = executionMode.strip().toLowerCase();
        return "fast".equals(mode) || "pro".equals(mode);
    }

    private boolean compressionPointActive(ChatConversationEntity conv) {
        if (conv == null) {
            return false;
        }
        return compressionPointActive(contextProperties, conv.getKind(), conv.getExecutionPreference());
    }

    /**
     * 压缩点模式压缩（§5.5.2 重组）：Near 保底 {@code nearKeepRounds} 原文 +
     * Mid {@code midKeepRounds} 摘要 + 其余折叠进 Far；压缩点 = {@code far_folded_msg_ids}
     * 前移至折叠区边界（C3 一次性重建）。两次压缩间 Near 只增不减。
     * <p>P（{@code far_folded_msg_ids} = 退役边界）与 S（{@code far_summarized_msg_ids} = 已折叠进
     * far_summary 的子集）分离：组装侧同步推进（{@link #advanceCompressionPoint}）把轮次退役进 P 而不折叠，
     * 这些 P\S 的「间隙」轮次在本轮末异步压缩时补折叠，保证信息不丢。
     */
    private void compressByCompressionPoint(
            String userId, String tenantId, String convId, List<SessionTurn> history, ChatConversationEntity conv) {
        ContextProperties.L1 l1 = contextProperties.getL1();
        ConversationContextL1Entity existing = l1Store.find(convId).orElse(null);
        String farSummary = l1Store.farSummaryOf(existing);
        String l2Block = resolveL2Block(conv, userId, tenantId);
        LinkedHashSet<String> foldedIds = new LinkedHashSet<>(l1Store.parseFarFoldedMsgIds(existing));
        LinkedHashSet<String> summarizedIds = new LinkedHashSet<>(
                l1Store.parseFarSummarizedMsgIds(existing, Set.copyOf(foldedIds)));
        Map<String, String> midAnswers = new HashMap<>(l1Store.parseMidAnswers(existing));

        List<List<SessionTurn>> rounds = groupRounds(history);
        List<List<SessionTurn>> liveRounds = new ArrayList<>();
        List<List<SessionTurn>> gapRounds = new ArrayList<>();
        for (List<SessionTurn> round : rounds) {
            if (!roundFullyFolded(round, foldedIds)) {
                liveRounds.add(round);
            } else if (!roundFullyFolded(round, summarizedIds)) {
                gapRounds.add(round);
            }
        }
        String effectiveModel = modelSceneResolver.resolve(ModelSceneKey.CHAT.key(), null).effectiveModel();
        int modelWindow = modelWindowCache.windowFor(effectiveModel);
        // chat 二期（§5.5.7 差异表）：Near/Mid 参数按 kind 分化——task 2+2+Far≤10k / chat 4+4+Far
        String kind = conv != null ? conv.getKind() : null;
        ContextProperties.L1.CompressionPoint point = l1.getCompressionPoint();
        if (point == null) {
            // 配置热更新瞬态缺失：保守跳过本轮压缩（读路径分区不受影响）
            return;
        }
        boolean isTask = "task".equals(kind);
        int nearKeep = Math.max(1, isTask ? point.getNearKeepRounds() : point.getChatNearKeepRounds());
        int midKeep = Math.max(0, isTask ? point.getMidKeepRounds() : point.getChatMidKeepRounds());
        boolean reorganize = shouldCompressAtPoint(liveRounds, l1, modelWindow, tokenEstimator, l2Block, farSummary)
                && liveRounds.size() > nearKeep;

        List<List<SessionTurn>> newMidRounds = new ArrayList<>();
        List<List<SessionTurn>> toFoldRounds = new ArrayList<>();
        if (reorganize) {
            int nearStart = liveRounds.size() - nearKeep;
            int midStart = Math.max(0, nearStart - midKeep);
            // ≤10k 硬预算（v15 / §5.5 ⑮）仅 task：总量超限先降级最旧 Mid 为折叠、再折叠最旧 Near（保底 1 轮）；
            // chat 无硬预算（4+4+Far），靠组装侧 Budget 退役并入收敛
            if (isTask) {
                int[] starts = enforcePostCompactBudget(
                        liveRounds, midStart, nearStart, l1, farSummary, tokenEstimator, midAnswers);
                midStart = starts[0];
                nearStart = starts[1];
            }
            newMidRounds = new ArrayList<>(liveRounds.subList(midStart, nearStart));
            toFoldRounds = new ArrayList<>(liveRounds.subList(0, midStart));
        }

        // 待折叠 = 间隙轮（同步推进退役）+ 本次重组退役轮，一次 LLM 折叠
        List<SessionTurn> toFold = new ArrayList<>();
        collectTurns(toFold, gapRounds);
        collectTurns(toFold, toFoldRounds);

        if (!reorganize && toFold.isEmpty()) {
            return;
        }

        Set<String> midAssistantIds = new HashSet<>();
        for (List<SessionTurn> round : newMidRounds) {
            for (SessionTurn turn : round) {
                if (!"assistant".equals(turn.role()) || !StringUtils.hasText(turn.messageId())) {
                    continue;
                }
                midAssistantIds.add(turn.messageId());
                if (midAnswers.containsKey(turn.messageId())) {
                    continue;
                }
                String summary = resolveMidAnswer(conv, turn.content());
                if (StringUtils.hasText(summary)) {
                    midAnswers.put(turn.messageId(), summary.strip());
                }
            }
        }
        if (reorganize) {
            midAnswers.keySet().removeIf(id -> !midAssistantIds.contains(id));
        } else {
            // 仅补折叠间隙轮：剔除其 assistant 的 mid 摘要（已进 Far）
            for (SessionTurn turn : toFold) {
                if ("assistant".equals(turn.role()) && StringUtils.hasText(turn.messageId())) {
                    midAnswers.remove(turn.messageId());
                }
            }
        }

        if (!toFold.isEmpty()) {
            String folded = foldFar(farSummary, toFold, l2Block);
            if (StringUtils.hasText(folded)) {
                farSummary = folded.strip();
            }
            for (SessionTurn turn : toFold) {
                foldedIds.add(turn.messageId());
                summarizedIds.add(turn.messageId());
            }
        }

        l1Store.upsert(userId, tenantId, convId, midAnswers, farSummary,
                foldedIds, summarizedIds, nearKeep, midKeep);
        log.debug("[ContextL1] compression-point conv={} midKeys={} farLen={} folded={} summarized={}",
                convId, midAnswers.size(), farSummary != null ? farSummary.length() : 0,
                foldedIds.size(), summarizedIds.size());
    }

    private static void collectTurns(List<SessionTurn> sink, List<List<SessionTurn>> rounds) {
        for (List<SessionTurn> round : rounds) {
            for (SessionTurn turn : round) {
                if (turn != null && StringUtils.hasText(turn.messageId())) {
                    sink.add(turn);
                }
            }
        }
    }

    /**
     * ≤10k 硬预算（v15 / §5.5 ⑮）：压缩重组后 Near+Mid+Far 总量超
     * {@code task-post-compact-budget} → 先把最旧 Mid 轮降为折叠，再激进折叠最旧 Near（保底 1 轮）。
     * 返回调整后的 {@code [midStart, nearStart]}。
     */
    static int[] enforcePostCompactBudget(
            List<List<SessionTurn>> liveRounds, int midStart, int nearStart,
            ContextProperties.L1 l1, String farSummary, TokenEstimator estimator,
            Map<String, String> midAnswers) {
        int budget = l1 != null && l1.getCompressionPoint() != null
                ? l1.getCompressionPoint().getTaskPostCompactBudget()
                : 0;
        if (budget <= 0 || estimator == null || liveRounds == null || liveRounds.isEmpty()) {
            return new int[]{midStart, nearStart};
        }
        double midRatio = l1.getMidCompressRatio() > 0 ? l1.getMidCompressRatio() : 0.15;
        Map<String, String> answers = midAnswers != null ? midAnswers : Map.of();
        int size = liveRounds.size();
        while (true) {
            int total = estimator.count(farSummary);
            for (int i = midStart; i < nearStart; i++) {
                total += estimateMidRound(liveRounds.get(i), answers, midRatio, estimator);
            }
            for (int i = nearStart; i < size; i++) {
                total += roundTokens(liveRounds.get(i), estimator);
            }
            if (total <= budget) {
                break;
            }
            if (midStart < nearStart) {
                midStart++;
            } else if (nearStart < size - 1) {
                nearStart++;
            } else {
                break;
            }
        }
        return new int[]{midStart, nearStart};
    }

    private static int roundTokens(List<SessionTurn> round, TokenEstimator estimator) {
        int n = 0;
        for (SessionTurn t : round) {
            if (t != null) {
                n += estimator.count(t.content());
            }
        }
        return n;
    }

    private static int estimateMidRound(
            List<SessionTurn> round, Map<String, String> answers, double midRatio, TokenEstimator estimator) {
        int n = 0;
        for (SessionTurn t : round) {
            if (t == null) {
                continue;
            }
            if ("assistant".equals(t.role()) && StringUtils.hasText(t.messageId())
                    && answers.containsKey(t.messageId())) {
                n += estimator.count(answers.get(t.messageId()));
            } else if ("assistant".equals(t.role())) {
                n += (int) (estimator.count(t.content()) * midRatio);
            } else {
                n += estimator.count(t.content());
            }
        }
        return n;
    }

    /**
     * 同步推进压缩点（§5.5 ① / task-scene §4.2.1）：组装侧检测到预算溢出时同步调用——
     * 零 LLM、纯写库：把 P（{@code far_folded_msg_ids}）前移，退役最旧活跃轮次，仅保留
     * {@code nearKeepRounds} 活跃轮（按 kind 分化：task 2 / chat 4）。退役轮进入 P\S
     * （{@code far_summarized_msg_ids} 不动），待轮末异步压缩补折叠；本轮按新 P 组装
     * （Near 收缩、Mid/Far 暂用旧值），一次 KV miss、token 立即可控。
     * 返回推进后的压缩点集合（无需推进时返回原集合）。
     */
    public Set<String> advanceCompressionPoint(
            String userId, String tenantId, String convId, String kind, List<SessionTurn> history) {
        if (!StringUtils.hasText(convId) || history == null || history.isEmpty()) {
            return Set.of();
        }
        Object lock = compressLocks.computeIfAbsent(convId, id -> new Object());
        synchronized (lock) {
            ConversationContextL1Entity existing = l1Store.find(convId).orElse(null);
            Set<String> oldFolded = l1Store.parseFarFoldedMsgIds(existing);
            LinkedHashSet<String> foldedIds = new LinkedHashSet<>(oldFolded);
            LinkedHashSet<String> summarizedIds = new LinkedHashSet<>(
                    l1Store.parseFarSummarizedMsgIds(existing, oldFolded));
            List<List<SessionTurn>> liveRounds = new ArrayList<>();
            for (List<SessionTurn> round : groupRounds(history)) {
                if (!roundFullyFolded(round, foldedIds)) {
                    liveRounds.add(round);
                }
            }
            ContextProperties.L1.CompressionPoint point = contextProperties.getL1().getCompressionPoint();
            if (point == null) {
                // 配置热更新瞬态缺失：不推进（读路径维持旧分区）
                return Collections.unmodifiableSet(foldedIds);
            }
            boolean isTask = "task".equals(kind);
            int nearKeep = Math.max(1, isTask ? point.getNearKeepRounds() : point.getChatNearKeepRounds());
            int midKeep = Math.max(0, isTask ? point.getMidKeepRounds() : point.getChatMidKeepRounds());
            if (liveRounds.size() <= nearKeep) {
                return Collections.unmodifiableSet(foldedIds);
            }
            List<List<SessionTurn>> toRetire = liveRounds.subList(0, liveRounds.size() - nearKeep);
            for (List<SessionTurn> round : toRetire) {
                for (SessionTurn turn : round) {
                    if (StringUtils.hasText(turn.messageId())) {
                        foldedIds.add(turn.messageId());
                    }
                }
            }
            l1Store.upsert(userId, tenantId, convId,
                    l1Store.parseMidAnswers(existing), l1Store.farSummaryOf(existing),
                    foldedIds, summarizedIds, nearKeep, midKeep);
            log.info("[ContextL1] compression-point 同步推进 conv={} kind={} retiredRounds={} folded={}",
                    convId, kind, liveRounds.size() - nearKeep, foldedIds.size());
            return Collections.unmodifiableSet(foldedIds);
        }
    }

    /** 压缩点模式触发：压缩点后活跃轮次（含 Far/L2 基座）超阈值，或活跃轮数超宽限兜底。 */
    public static boolean shouldCompressAtPoint(
            List<List<SessionTurn>> liveRounds,
            ContextProperties.L1 l1,
            int modelWindow,
            TokenEstimator estimator,
            String l2Block,
            String farSummary) {
        if (liveRounds == null || liveRounds.isEmpty() || l1 == null || estimator == null || modelWindow <= 0) {
            return false;
        }
        int effective = estimator.effectiveCount(flatten(liveRounds), l1.getTokenSafetyFactor());
        effective += estimator.count(l2Block) + estimator.count(farSummary);
        int threshold = (int) (modelWindow * l1.getMaxTokensRatio());
        if (effective > threshold) {
            return true;
        }
        return liveRounds.size() > Math.max(1, l1.getTurnBackstop());
    }

    /** 压缩点模式分区：Far = 已折叠轮次；Mid = 有摘要的轮次；Near = 压缩点之后其余原文（只增不减）。 */
    public static WindowBands partitionByPoint(
            List<SessionTurn> history, Set<String> foldedMsgIds, Set<String> midAssistantIds) {
        if (history == null || history.isEmpty()) {
            return new WindowBands(List.of(), List.of(), List.of());
        }
        Set<String> folded = foldedMsgIds != null ? foldedMsgIds : Set.of();
        Set<String> midIds = midAssistantIds != null ? midAssistantIds : Set.of();
        List<List<SessionTurn>> far = new ArrayList<>();
        List<List<SessionTurn>> mid = new ArrayList<>();
        List<List<SessionTurn>> near = new ArrayList<>();
        for (List<SessionTurn> round : groupRounds(history)) {
            if (roundFullyFolded(round, folded)) {
                far.add(round);
            } else if (roundAssistantIn(round, midIds)) {
                mid.add(round);
            } else {
                near.add(round);
            }
        }
        return new WindowBands(flatten(far), flatten(mid), flatten(near));
    }

    /** 轮次已折叠：至少含一个 msgId 且全部 msgId 均在折叠集内。 */
    public static boolean roundFullyFolded(List<SessionTurn> round, Set<String> foldedMsgIds) {
        if (round == null || round.isEmpty() || foldedMsgIds == null || foldedMsgIds.isEmpty()) {
            return false;
        }
        boolean hasId = false;
        for (SessionTurn turn : round) {
            if (turn == null || !StringUtils.hasText(turn.messageId())) {
                continue;
            }
            hasId = true;
            if (!foldedMsgIds.contains(turn.messageId())) {
                return false;
            }
        }
        return hasId;
    }

    private static boolean roundAssistantIn(List<SessionTurn> round, Set<String> assistantIds) {
        if (round == null || assistantIds == null || assistantIds.isEmpty()) {
            return false;
        }
        for (SessionTurn turn : round) {
            if (turn != null && "assistant".equals(turn.role())
                    && StringUtils.hasText(turn.messageId())
                    && assistantIds.contains(turn.messageId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按会话 kind 选 L2 scope：task → workspace 块；chat/缺省 → user 块。
     * 反查失败/无记录降级 user scope（不抛错，不阻断压缩）。
     */
    private String resolveL2Block(ChatConversationEntity conv, String userId, String tenantId) {
        if (conv != null && "task".equals(conv.getKind())) {
            return l2StateStore.assembleWorkspaceBlock(conv.getWorkspaceId(), tenantId);
        }
        return l2StateStore.assembleSystemBlock(userId, tenantId);
    }

    /** token 阈值为主 + 轮次宽限兜底：effectiveToken > window×ratio 或轮数 > turnBackstop。 */
    public static boolean shouldCompress(
            List<SessionTurn> history,
            ContextProperties.L1 l1,
            int modelWindow,
            TokenEstimator estimator) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        if (l1 == null || estimator == null || modelWindow <= 0) {
            return false;
        }
        int effective = estimator.effectiveCount(history, l1.getTokenSafetyFactor());
        int threshold = (int) (modelWindow * l1.getMaxTokensRatio());
        if (effective > threshold) {
            return true;
        }
        return countRounds(history) > Math.max(1, l1.getTurnBackstop());
    }

    /**
     * 按问答轮次划分 Far / Mid / Near。一轮以 user 消息起头，后续非 user 归入该轮；
     * 若开头无 user，则先攒一条「残轮」。
     */
    public static WindowBands partition(List<SessionTurn> history, int nearTurns, int midTurns) {
        if (history == null || history.isEmpty()) {
            return new WindowBands(List.of(), List.of(), List.of());
        }
        List<List<SessionTurn>> rounds = groupRounds(history);
        int nearN = Math.max(1, nearTurns);
        int midN = Math.max(0, midTurns);
        int size = rounds.size();
        int nearStart = Math.max(0, size - nearN);
        int midStart = Math.max(0, nearStart - midN);
        List<SessionTurn> far = flatten(rounds.subList(0, midStart));
        List<SessionTurn> mid = flatten(rounds.subList(midStart, nearStart));
        List<SessionTurn> near = flatten(rounds.subList(nearStart, size));
        return new WindowBands(far, mid, near);
    }

    /** 一轮 = user + 其后连续非 user；孤立开头的 assistant 自成残轮。 */
    public static List<List<SessionTurn>> groupRounds(List<SessionTurn> history) {
        List<List<SessionTurn>> rounds = new ArrayList<>();
        List<SessionTurn> current = null;
        for (SessionTurn turn : history) {
            if (turn == null) {
                continue;
            }
            if ("user".equals(turn.role())) {
                current = new ArrayList<>();
                current.add(turn);
                rounds.add(current);
            } else if (current == null) {
                current = new ArrayList<>();
                current.add(turn);
                rounds.add(current);
            } else {
                current.add(turn);
            }
        }
        return rounds;
    }

    public static int countRounds(List<SessionTurn> history) {
        return groupRounds(history).size();
    }

    /**
     * 自适应 Near 轮数：默认 nearTurns 保交互完整；组装估算超阈值时逐轮缩小 Near（溢出转 Mid），
     * 直到 token 降到阈值内或 Near 缩到 1 轮（当前交互永不丢）。
     * Mid 摘要后 token 按 midCompressRatio 估算。
     */
    public static int resolveNearRounds(
            List<SessionTurn> history,
            ContextProperties.L1 l1,
            int modelWindow,
            TokenEstimator estimator,
            String l2Block,
            String farSummary) {
        int defaultNear = Math.max(1, l1.getNearTurns());
        if (history == null || history.isEmpty() || estimator == null || modelWindow <= 0) {
            return defaultNear;
        }
        int threshold = (int) (modelWindow * l1.getMaxTokensRatio());
        int totalRounds = countRounds(history);
        int near = Math.min(defaultNear, totalRounds);
        double midRatio = l1.getMidCompressRatio() > 0 ? l1.getMidCompressRatio() : 0.15;

        while (near > 1) {
            int assembled = estimateAssembled(history, l1, estimator, l2Block, farSummary, near, midRatio);
            if (assembled <= threshold) {
                break;
            }
            near--;
        }
        return near;
    }

    /** 估算组装后总 token：L2 + Far + Mid(摘要后估算) + Near(原文)。 */
    private static int estimateAssembled(
            List<SessionTurn> history,
            ContextProperties.L1 l1,
            TokenEstimator estimator,
            String l2Block,
            String farSummary,
            int nearRounds,
            double midRatio) {
        WindowBands bands = partition(history, nearRounds, l1.getMidTurns());
        int n = estimator.count(l2Block) + estimator.count(farSummary);
        int midRaw = 0;
        for (SessionTurn t : bands.mid()) {
            if (t != null) {
                midRaw += estimator.count(t.content());
            }
        }
        n += (int) (midRaw * midRatio);
        for (SessionTurn t : bands.near()) {
            if (t != null) {
                n += estimator.count(t.content());
            }
        }
        return n;
    }

    private static List<SessionTurn> flatten(List<List<SessionTurn>> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return List.of();
        }
        List<SessionTurn> out = new ArrayList<>();
        for (List<SessionTurn> round : rounds) {
            out.addAll(round);
        }
        return List.copyOf(out);
    }

    private String compressMidAnswer(String assistantContent) {
        String system = catalogHolder.requireText(MID_COMPRESS_PROMPT);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextL1] missing catalog {}", MID_COMPRESS_PROMPT);
            return "";
        }
        try {
            return llmGatewayClient.complete(system, assistantContent != null ? assistantContent : "");
        } catch (Exception e) {
            log.warn("[ContextL1] mid-compress LLM 失败: {}", e.getMessage());
            return "";
        }
    }

    private static final int MAX_CONCLUSION_LEN = 120;

    /** Mid 摘要按场景分流（§5.5.8 / §6.5）：task 短结论机械截取（零 LLM），chat 结论优先 1-3 句 */
    private String resolveMidAnswer(ChatConversationEntity conv, String assistantContent) {
        if (conv != null && "task".equals(conv.getKind())) {
            return extractShortConclusion(assistantContent);
        }
        return compressMidAnswer(assistantContent);
    }

    /** task Mid 短结论（§6.5 v9）：原文前 2 句机械截取，零 LLM；总长封顶 */
    static String extractShortConclusion(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String t = content.strip();
        int end = 0;
        int sentenceEnds = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n') {
                end = i + 1;
                if (++sentenceEnds >= 2) {
                    break;
                }
            }
        }
        if (end == 0) {
            // 无断句：整体按上限截断（带省略号语义）
            return t.length() <= MAX_CONCLUSION_LEN ? t : t.substring(0, MAX_CONCLUSION_LEN) + "…";
        }
        if (end > MAX_CONCLUSION_LEN) {
            return t.substring(0, MAX_CONCLUSION_LEN) + "…";
        }
        return t.substring(0, end).strip();
    }

    /**
     * 仅折叠 newFarTurns（已有摘要作前缀）；附带现行 L2，冲突以 L2 为准。
     * 调用方保证不含已折叠轮次。
     */
    private String foldFar(String previousFarSummary, List<SessionTurn> newFarTurns, String l2Block) {
        String system = catalogHolder.requireText(FAR_FOLD_PROMPT);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextL1] missing catalog {}", FAR_FOLD_PROMPT);
            return previousFarSummary != null ? previousFarSummary : "";
        }
        StringBuilder user = new StringBuilder();
        if (StringUtils.hasText(l2Block)) {
            user.append("【现行 L2 用户状态 · 权威】\n").append(l2Block.strip()).append("\n\n");
        } else {
            user.append("【现行 L2 用户状态 · 权威】\n（无）\n\n");
        }
        if (StringUtils.hasText(previousFarSummary)) {
            user.append("【已有远窗摘要】\n").append(previousFarSummary.strip()).append("\n\n");
        }
        user.append("【待折叠对话】\n");
        for (SessionTurn turn : newFarTurns) {
            user.append(turn.role()).append(": ").append(turn.content()).append('\n');
        }
        try {
            return llmGatewayClient.complete(system, user.toString());
        } catch (Exception e) {
            log.warn("[ContextL1] far-fold LLM 失败: {}", e.getMessage());
            return previousFarSummary != null ? previousFarSummary : "";
        }
    }

    public record WindowBands(List<SessionTurn> far, List<SessionTurn> mid, List<SessionTurn> near) {
        public WindowBands {
            far = far != null ? List.copyOf(far) : List.of();
            mid = mid != null ? List.copyOf(mid) : List.of();
            near = near != null ? List.copyOf(near) : List.of();
        }
    }
}
