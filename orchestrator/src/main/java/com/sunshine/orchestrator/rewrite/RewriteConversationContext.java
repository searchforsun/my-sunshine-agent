package com.sunshine.orchestrator.rewrite;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryContext;
import org.springframework.util.StringUtils;

/** Query 改写 LLM 用户消息：拼接 LTM/MTM/STM，供 intent 等场景消解指代 */
public final class RewriteConversationContext {

    private RewriteConversationContext() {
    }

    public static String formatForPrompt(MemoryContext memory) {
        if (memory == null || !memory.hasAnyLayer()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(memory.ltmSnippet())) {
            sb.append("长期记忆摘要：\n").append(memory.ltmSnippet().strip()).append("\n\n");
        }
        if (StringUtils.hasText(memory.mtmSnippet())) {
            sb.append("中期记忆摘要：\n").append(memory.mtmSnippet().strip()).append("\n\n");
        }
        if (memory.stmTurns() != null && !memory.stmTurns().isEmpty()) {
            sb.append("近期对话：\n");
            for (ChatTurn turn : memory.stmTurns()) {
                if (turn.content() == null || turn.content().isBlank()) {
                    continue;
                }
                String roleLabel = roleLabel(turn.role());
                sb.append(roleLabel).append("：").append(turn.content().strip()).append("\n");
            }
        }
        return sb.toString().strip();
    }

    static String buildUserMessage(String originalQuery, MemoryContext memory) {
        String query = originalQuery != null ? originalQuery.strip() : "";
        String context = formatForPrompt(memory);
        if (!StringUtils.hasText(context)) {
            return "用户输入：" + query;
        }
        return context + "\n\n用户输入：" + query;
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
