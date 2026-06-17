package com.sunshine.orchestrator.memory;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.stm.StmBoundaryFormatter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryMessageBuilderTest {

    @Test
    void appendStmTurns_fullRolesNotExcerpt() {
        MemoryProperties props = new MemoryProperties();
        MemoryContext memory = new MemoryContext("", "", List.of(
                new ChatTurn("user", "写 cpp 快排"),
                new ChatTurn("assistant", "完整 cpp 代码块")));

        List<Map<String, Object>> messages = new ArrayList<>();
        MemoryMessageBuilder.appendStmTurns(messages, memory, props);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).get("role")).isEqualTo("system");
        assertThat(messages.get(0).get("content").toString()).contains("STM");
        assertThat(messages.get(1)).containsEntry("role", "user");
        assertThat(messages.get(2)).containsEntry("role", "assistant");
        assertThat(messages.get(2).get("content")).isEqualTo("完整 cpp 代码块");
    }

    @Test
    void stmBoundaryFormatter_includesHeaderAndPreamble() {
        MemoryProperties props = new MemoryProperties();
        String boundary = StmBoundaryFormatter.format(props);
        assertThat(boundary).contains("[本会话近期对话 · STM]");
        assertThat(boundary).contains("完整对话轮次");
    }
}
