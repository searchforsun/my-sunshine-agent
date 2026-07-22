package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨轮上下文读路径：Task 4 仅 Near（尾部窗口 + 字符预算整条丢弃）；L2/Mid/Far/L3 后续 Task 填充。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextAssembler {

    private final ContextProperties contextProperties;

    public AssembledContext assemble(AssembleRequest request) {
        if (!contextProperties.isEnabled()) {
            return AssembledContext.empty();
        }
        List<ChatTurn> source = sanitizeTurns(request.history());
        ContextProperties.L1 l1 = contextProperties.getL1();
        List<ChatTurn> near = selectNearWindow(source, l1.getNearTurns(), l1.getMaxChars());
        log.debug("[Context] assemble conv={} nearTurns={}",
                request.conversationId(), near.size());
        return new AssembledContext("", "", List.of(), near, "");
    }

    /** 取尾部 nearTurns 条；超 maxChars 从头整条丢弃（不截断单条 content）。 */
    static List<ChatTurn> selectNearWindow(List<ChatTurn> history, int nearTurns, int maxChars) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, nearTurns);
        List<ChatTurn> tail = history.size() <= limit
                ? new ArrayList<>(history)
                : new ArrayList<>(history.subList(history.size() - limit, history.size()));
        return trimByChars(tail, maxChars);
    }

    static List<ChatTurn> trimByChars(List<ChatTurn> turns, int maxChars) {
        if (maxChars <= 0 || turns.isEmpty()) {
            return List.copyOf(turns);
        }
        int total = turns.stream().mapToInt(t -> t.content() != null ? t.content().length() : 0).sum();
        if (total <= maxChars) {
            return List.copyOf(turns);
        }
        List<ChatTurn> out = new ArrayList<>(turns);
        while (!out.isEmpty() && total > maxChars) {
            ChatTurn removed = out.remove(0);
            total -= removed.content() != null ? removed.content().length() : 0;
        }
        return List.copyOf(out);
    }

    private static List<ChatTurn> sanitizeTurns(List<ChatTurn> turns) {
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
            List<ChatTurn> history,
            String currentUserQuery
    ) {
    }
}
