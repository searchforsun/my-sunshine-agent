package com.sunshine.orchestrator.context.l1;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.context.l2.L2StateStore;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
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
        ContextProperties.L1 l1 = contextProperties.getL1();
        int nearN = Math.max(1, l1.getNearTurns());
        int midN = Math.max(0, l1.getMidTurns());
        int maxChars = l1.getMaxChars();
        if (!shouldCompress(history, nearN, midN, maxChars)) {
            return;
        }
        WindowBands bands = partition(history, nearN, midN);
        ConversationContextL1Entity existing = l1Store.find(convId).orElse(null);
        Map<String, String> midAnswers = new HashMap<>(l1Store.parseMidAnswers(existing));
        String farSummary = l1Store.farSummaryOf(existing);
        LinkedHashSet<String> foldedIds = new LinkedHashSet<>(l1Store.parseFarFoldedMsgIds(existing));

        Set<String> midAssistantIds = new HashSet<>();
        for (SessionTurn turn : bands.mid()) {
            if (!"assistant".equals(turn.role()) || !StringUtils.hasText(turn.messageId())) {
                continue;
            }
            midAssistantIds.add(turn.messageId());
            if (midAnswers.containsKey(turn.messageId())) {
                continue;
            }
            String summary = compressMidAnswer(turn.content());
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
            String l2Block = l2StateStore.assembleSystemBlock(userId, tenantId);
            String folded = foldFar(farSummary, newToFold, l2Block);
            if (StringUtils.hasText(folded)) {
                farSummary = folded.strip();
            }
            for (SessionTurn turn : newToFold) {
                foldedIds.add(turn.messageId());
            }
        }

        l1Store.upsert(userId, tenantId, convId, midAnswers, farSummary, foldedIds, nearN, midN);
        log.debug("[ContextL1] compressed conv={} midKeys={} farLen={} folded={}",
                convId, midAnswers.size(), farSummary != null ? farSummary.length() : 0, foldedIds.size());
    }

    /** 混合触发：超 near+mid **问答轮次**，或总字符超预算。 */
    public static boolean shouldCompress(List<SessionTurn> history, int nearTurns, int midTurns, int maxChars) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int turnCap = Math.max(1, nearTurns) + Math.max(0, midTurns);
        if (countRounds(history) > turnCap) {
            return true;
        }
        if (maxChars <= 0) {
            return false;
        }
        int chars = history.stream()
                .mapToInt(t -> t.content() != null ? t.content().length() : 0)
                .sum();
        return chars > maxChars;
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
