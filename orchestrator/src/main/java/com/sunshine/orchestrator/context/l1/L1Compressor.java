package com.sunshine.orchestrator.context.l1;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.SessionTurn;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * L1 Mid/Far 压缩：逼近字符预算或轮次超 near+mid 时，异步写 mid_answers / far_summary。
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
    private final PromptCatalogHolder catalogHolder;

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

        for (SessionTurn turn : bands.mid()) {
            if (!"assistant".equals(turn.role()) || !StringUtils.hasText(turn.messageId())) {
                continue;
            }
            if (midAnswers.containsKey(turn.messageId())) {
                continue;
            }
            String summary = compressMidAnswer(turn.content());
            if (StringUtils.hasText(summary)) {
                midAnswers.put(turn.messageId(), summary.strip());
            }
        }

        if (!bands.far().isEmpty()) {
            String folded = foldFar(farSummary, bands.far());
            if (StringUtils.hasText(folded)) {
                farSummary = folded.strip();
            }
        }

        l1Store.upsert(userId, tenantId, convId, midAnswers, farSummary, nearN, midN);
        log.debug("[ContextL1] compressed conv={} midKeys={} farLen={}",
                convId, midAnswers.size(), farSummary != null ? farSummary.length() : 0);
    }

    /** 混合触发：超 near+mid 轮次，或总字符超预算。 */
    public static boolean shouldCompress(List<SessionTurn> history, int nearTurns, int midTurns, int maxChars) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int turnCap = Math.max(1, nearTurns) + Math.max(0, midTurns);
        if (history.size() > turnCap) {
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

    public static WindowBands partition(List<SessionTurn> history, int nearTurns, int midTurns) {
        if (history == null || history.isEmpty()) {
            return new WindowBands(List.of(), List.of(), List.of());
        }
        int nearN = Math.max(1, nearTurns);
        int midN = Math.max(0, midTurns);
        int size = history.size();
        int nearStart = Math.max(0, size - nearN);
        int midStart = Math.max(0, nearStart - midN);
        List<SessionTurn> far = midStart > 0
                ? List.copyOf(history.subList(0, midStart))
                : List.of();
        List<SessionTurn> mid = midStart < nearStart
                ? List.copyOf(history.subList(midStart, nearStart))
                : List.of();
        List<SessionTurn> near = List.copyOf(history.subList(nearStart, size));
        return new WindowBands(far, mid, near);
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

    private String foldFar(String previousFarSummary, List<SessionTurn> farTurns) {
        String system = catalogHolder.requireText(FAR_FOLD_PROMPT);
        if (!StringUtils.hasText(system)) {
            log.warn("[ContextL1] missing catalog {}", FAR_FOLD_PROMPT);
            return previousFarSummary != null ? previousFarSummary : "";
        }
        StringBuilder user = new StringBuilder();
        if (StringUtils.hasText(previousFarSummary)) {
            user.append("【已有远窗摘要】\n").append(previousFarSummary.strip()).append("\n\n");
        }
        user.append("【待折叠对话】\n");
        for (SessionTurn turn : farTurns) {
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
