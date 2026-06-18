package com.sunshine.orchestrator.memory;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.stm.StmBoundaryFormatter;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 方案 C：LTM/MTM → system 摘要块；STM → 边界 system + 完整 user/assistant 轮次；当前提问单独标记。
 */
public final class MemoryMessageBuilder {

    private MemoryMessageBuilder() {
    }

    public static List<Map<String, Object>> buildPrefix(
            AgentPromptProperties prompts, MemoryProperties memoryProperties, MemoryContext memory) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", prompts.systemPromptOrEmpty()));
        if (memoryProperties != null && StringUtils.hasText(memoryProperties.getLayerPrompt())) {
            messages.add(Map.of("role", "system", "content", memoryProperties.getLayerPrompt().strip()));
        }
        appendLongTermLayers(messages, memory);
        return messages;
    }

    /** LTM / MTM 摘要层 */
    public static void appendLongTermLayers(List<Map<String, Object>> messages, MemoryContext memory) {
        if (memory == null) {
            return;
        }
        addSystemIfText(messages, memory.ltmSnippet());
        addSystemIfText(messages, memory.mtmSnippet());
    }

    /** STM：边界 system + 完整对话轮次（不截断单条 content） */
    public static void appendStmTurns(
            List<Map<String, Object>> messages, MemoryContext memory, MemoryProperties memoryProperties) {
        if (memory == null || memory.stmTurns() == null || memory.stmTurns().isEmpty()) {
            return;
        }
        String boundary = StmBoundaryFormatter.format(memoryProperties);
        if (StringUtils.hasText(boundary)) {
            messages.add(Map.of("role", "system", "content", boundary));
        }
        for (ChatTurn turn : memory.stmTurns()) {
            if (turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if ("assistant".equals(turn.role()) || "user".equals(turn.role())) {
                messages.add(Map.of("role", turn.role(), "content", turn.content()));
            }
        }
    }

    public static String formatCurrentUser(String userMessage, MemoryProperties memoryProperties) {
        String content = userMessage != null ? userMessage.strip() : "";
        if (memoryProperties == null || !StringUtils.hasText(memoryProperties.getCurrentUserMarker())) {
            return content;
        }
        return memoryProperties.getCurrentUserMarker().strip() + "\n" + content;
    }

    private static void addSystemIfText(List<Map<String, Object>> messages, String text) {
        if (StringUtils.hasText(text)) {
            messages.add(Map.of("role", "system", "content", text.strip()));
        }
    }
}
