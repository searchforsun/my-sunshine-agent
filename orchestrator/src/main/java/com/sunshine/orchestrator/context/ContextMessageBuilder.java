package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * AssembledContext → Gateway messages。
 * 顺序：system(ProjectGuide) → system(layerPrompt/usage) → system(Far) → Mid → Near
 * → system(TaskListRestore) → system(L2) → system(L3)。
 * L2 后置（紧邻 TaskListRestore，历史轮次之后）：L2 由每轮写路径静默更新、条目带 TTL，
 * 内容会随轮次变化——置于历史之前会让任一次写入/过期击穿其后全部历史轮次的前缀缓存；
 * 后置后击穿半径收敛到尾部动态段。layerPrompt/usageRules 是静态文本，留在前缀稳定区。
 */
public final class ContextMessageBuilder {

    private ContextMessageBuilder() {
    }

    public static void appendAll(
            List<Map<String, Object>> messages,
            AssembledContext ctx,
            String layerPrompt,
            String usageRules) {
        if (messages == null) {
            return;
        }
        AssembledContext context = ctx != null ? ctx : AssembledContext.empty();
        addSystemIfText(messages, context.projectGuideBlock());
        addSystemIfText(messages, joinNonBlank(layerPrompt, usageRules));
        addSystemIfText(messages, context.farSummaryBlock());
        appendTurns(messages, context.midTurns());
        appendTurns(messages, context.nearTurns());
        addSystemIfText(messages, context.taskListRestoreBlock());
        addSystemIfText(messages, context.l2SystemBlock());
        addSystemIfText(messages, context.l3MaterialBlock());
    }

    public static String formatCurrentUser(String userMessage, String currentUserMarker) {
        String content = userMessage != null ? userMessage.strip() : "";
        if (!StringUtils.hasText(currentUserMarker)) {
            return content;
        }
        return currentUserMarker.strip() + "\n" + content;
    }

    private static void appendTurns(List<Map<String, Object>> messages, List<ChatTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return;
        }
        for (ChatTurn turn : turns) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if ("assistant".equals(turn.role()) || "user".equals(turn.role())) {
                messages.add(Map.of("role", turn.role(), "content", turn.content()));
            }
        }
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(part.strip());
        }
        return sb.toString();
    }

    private static void addSystemIfText(List<Map<String, Object>> messages, String text) {
        if (StringUtils.hasText(text)) {
            messages.add(Map.of("role", "system", "content", text.strip()));
        }
    }
}
