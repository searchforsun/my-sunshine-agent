package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextMessageBuilderTest {

    @Test
    void append_ordersL2_Far_Mid_Near_L3() {
        var ctx = new AssembledContext(
                "[用户状态 · L2]\n- preference: 简洁",
                "[更早对话 · Far]\n曾讨论差旅",
                List.of(new ChatTurn("user", "Q1"), new ChatTurn("assistant", "A1摘要")),
                List.of(new ChatTurn("user", "Q2"), new ChatTurn("assistant", "A2全文")),
                "[历史材料 · L3 · 可能过期]\n- …");
        List<Map<String, Object>> msgs = new ArrayList<>();
        ContextMessageBuilder.appendAll(msgs, ctx, "分层说明", "仅供指代");

        assertThat(msgs.get(0).get("role")).isEqualTo("system");
        assertThat(msgs.get(0).get("content").toString()).contains("L2");
        assertThat(msgs.get(0).get("content").toString()).contains("分层说明");
        assertThat(msgs.get(0).get("content").toString()).contains("仅供指代");

        assertThat(msgs.get(1).get("role")).isEqualTo("system");
        assertThat(msgs.get(1).get("content").toString()).contains("Far");

        assertThat(msgs.get(2)).containsEntry("role", "user").containsEntry("content", "Q1");
        assertThat(msgs.get(3)).containsEntry("role", "assistant").containsEntry("content", "A1摘要");
        assertThat(msgs.get(4)).containsEntry("role", "user").containsEntry("content", "Q2");
        assertThat(msgs.get(5)).containsEntry("role", "assistant").containsEntry("content", "A2全文");

        assertThat(msgs.get(6).get("role")).isEqualTo("system");
        assertThat(msgs.get(6).get("content").toString()).contains("L3");
        assertThat(msgs).hasSize(7);
    }

    @Test
    void formatCurrentUser_prefixesMarker() {
        assertThat(ContextMessageBuilder.formatCurrentUser("新问题", "【当前提问 · 仅此作答】"))
                .isEqualTo("【当前提问 · 仅此作答】\n新问题");
        assertThat(ContextMessageBuilder.formatCurrentUser("新问题", "")).isEqualTo("新问题");
    }

    @Test
    void appendAll_skipsBlankLayers() {
        List<Map<String, Object>> msgs = new ArrayList<>();
        ContextMessageBuilder.appendAll(
                msgs,
                new AssembledContext("", "", List.of(), List.of(new ChatTurn("user", "only-near")), ""),
                "",
                "");
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0)).containsEntry("role", "user").containsEntry("content", "only-near");
    }
}
