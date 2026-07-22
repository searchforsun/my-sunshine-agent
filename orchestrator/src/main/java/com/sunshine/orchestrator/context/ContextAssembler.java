package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Store;
import com.sunshine.orchestrator.context.l1.L1Compressor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 跨轮上下文读路径：L1 Near 全文 + Mid（user 全文 / assistant 摘要）+ Far system 块。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAssembler {

    private final ContextProperties contextProperties;
    private final ConversationContextL1Store l1Store;

    public AssembledContext assemble(AssembleRequest request) {
        if (!contextProperties.isEnabled()) {
            return AssembledContext.empty();
        }
        List<SessionTurn> source = sanitizeTurns(request.history());
        ContextProperties.L1 l1 = contextProperties.getL1();
        int nearN = Math.max(1, l1.getNearTurns());
        int midN = Math.max(0, l1.getMidTurns());
        L1Compressor.WindowBands bands = L1Compressor.partition(source, nearN, midN);

        ConversationContextL1Entity entity = l1Store.find(request.conversationId()).orElse(null);
        Map<String, String> midAnswers = l1Store.parseMidAnswers(entity);
        String farSummary = l1Store.farSummaryOf(entity);

        List<ChatTurn> mid = projectMid(bands.mid(), midAnswers);
        List<ChatTurn> near = toChatTurns(trimByChars(bands.near(), l1.getMaxChars()));
        String farBlock = StringUtils.hasText(farSummary) ? farSummary.strip() : "";

        log.debug("[Context] assemble conv={} far={} mid={} near={}",
                request.conversationId(),
                farBlock.isBlank() ? 0 : 1,
                mid.size(),
                near.size());
        return new AssembledContext("", farBlock, mid, near, "");
    }

    static List<ChatTurn> projectMid(List<SessionTurn> midBand, Map<String, String> midAnswers) {
        if (midBand == null || midBand.isEmpty()) {
            return List.of();
        }
        Map<String, String> answers = midAnswers != null ? midAnswers : Map.of();
        List<ChatTurn> out = new ArrayList<>(midBand.size());
        for (SessionTurn turn : midBand) {
            if ("assistant".equals(turn.role())
                    && StringUtils.hasText(turn.messageId())
                    && answers.containsKey(turn.messageId())) {
                out.add(new ChatTurn(turn.role(), answers.get(turn.messageId())));
            } else {
                out.add(turn.toChatTurn());
            }
        }
        return List.copyOf(out);
    }

    static List<ChatTurn> toChatTurns(List<SessionTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return turns.stream().map(SessionTurn::toChatTurn).toList();
    }

    /** 超 maxChars 从头整条丢弃（不截断单条 content）；入参为 Near 带 SessionTurn。 */
    static List<SessionTurn> trimByChars(List<SessionTurn> turns, int maxChars) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        if (maxChars <= 0) {
            return List.copyOf(turns);
        }
        int total = turns.stream().mapToInt(t -> t.content() != null ? t.content().length() : 0).sum();
        if (total <= maxChars) {
            return List.copyOf(turns);
        }
        List<SessionTurn> out = new ArrayList<>(turns);
        while (!out.isEmpty() && total > maxChars) {
            SessionTurn removed = out.remove(0);
            total -= removed.content() != null ? removed.content().length() : 0;
        }
        return List.copyOf(out);
    }

    private static List<SessionTurn> sanitizeTurns(List<SessionTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        return turns.stream()
                .filter(t -> t.content() != null && !t.content().isBlank())
                .toList();
    }

    public record AssembleRequest(
            String userId,
            String tenantId,
            String conversationId,
            List<SessionTurn> history,
            String currentUserQuery
    ) {
    }
}
