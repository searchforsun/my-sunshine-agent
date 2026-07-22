package com.sunshine.orchestrator.rewrite;

import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.conversation.ChatTurn;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** Query 改写 LLM 用户消息：拼接 AssembledContext 各层，供 intent 等场景消解指代 */
public final class RewriteConversationContext {

    private RewriteConversationContext() {
    }

    public static String formatForPrompt(AssembledContext memory) {
        if (memory == null || !memory.hasAnyLayer()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(memory.l2SystemBlock())) {
            sb.append("长期记忆摘要：\n").append(memory.l2SystemBlock().strip()).append("\n\n");
        }
        if (StringUtils.hasText(memory.farSummaryBlock())) {
            sb.append("中期记忆摘要：\n").append(memory.farSummaryBlock().strip()).append("\n\n");
        }
        List<ChatTurn> turns = mergeTurns(memory);
        if (!turns.isEmpty()) {
            sb.append("近期对话：\n");
            for (ChatTurn turn : turns) {
                if (turn.content() == null || turn.content().isBlank()) {
                    continue;
                }
                String roleLabel = roleLabel(turn.role());
                sb.append(roleLabel).append("：").append(turn.content().strip()).append("\n");
            }
        }
        if (StringUtils.hasText(memory.l3MaterialBlock())) {
            sb.append("历史材料：\n").append(memory.l3MaterialBlock().strip()).append("\n");
        }
        return sb.toString().strip();
    }

    static String buildUserMessage(String originalQuery, AssembledContext memory) {
        String query = originalQuery != null ? originalQuery.strip() : "";
        String context = formatForPrompt(memory);
        if (!StringUtils.hasText(context)) {
            return "用户输入：" + query;
        }
        return context + "\n\n用户输入：" + query;
    }

    private static List<ChatTurn> mergeTurns(AssembledContext memory) {
        List<ChatTurn> turns = new ArrayList<>();
        if (memory.midTurns() != null) {
            turns.addAll(memory.midTurns());
        }
        if (memory.nearTurns() != null) {
            turns.addAll(memory.nearTurns());
        }
        return turns;
    }

    private static String roleLabel(String role) {
        if ("user".equals(role)) {
            return "用户";
        }
        if ("assistant".equals(role)) {
            return "助手";
        }
        return role != null ? role : "未知";
    }
}
