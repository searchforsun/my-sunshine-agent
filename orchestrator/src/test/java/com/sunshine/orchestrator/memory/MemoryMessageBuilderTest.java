package com.sunshine.orchestrator.memory;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.stm.StmBoundaryFormatter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryMessageBuilderTest {

    private static final String STM_HEADER = "[本会话近期对话 · STM]";
    private static final String STM_PREAMBLE =
            "以下为同会话已结束轮次，仅供指代与消歧（如「这个 skill」「上述脚本」）。";
    private static final String USER_MARKER = "【当前提问 · 仅此作答】";

    @Test
    void appendStmTurns_fullRolesNotExcerpt() {
        MemoryContext memory = new MemoryContext("", "", List.of(
                new ChatTurn("user", "写 cpp 快排"),
                new ChatTurn("assistant", "完整 cpp 代码块")));

        List<Map<String, Object>> messages = new ArrayList<>();
        MemoryMessageBuilder.appendStmTurns(messages, memory, STM_HEADER, STM_PREAMBLE);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).get("role")).isEqualTo("system");
        assertThat(messages.get(0).get("content").toString()).contains("STM");
        assertThat(messages.get(1)).containsEntry("role", "user");
        assertThat(messages.get(2)).containsEntry("role", "assistant");
        assertThat(messages.get(2).get("content")).isEqualTo("完整 cpp 代码块");
    }

    @Test
    void stmBoundaryFormatter_includesHeaderAndPreamble() {
        String boundary = StmBoundaryFormatter.format(STM_HEADER, STM_PREAMBLE);
        assertThat(boundary).contains("[本会话近期对话 · STM]");
        assertThat(boundary).contains("仅供指代与消歧");
    }

    @Test
    void formatCurrentUser_prefixesMarker() {
        assertThat(MemoryMessageBuilder.formatCurrentUser("创建 csv", USER_MARKER))
                .startsWith("【当前提问 · 仅此作答】")
                .contains("创建 csv");
    }
}
