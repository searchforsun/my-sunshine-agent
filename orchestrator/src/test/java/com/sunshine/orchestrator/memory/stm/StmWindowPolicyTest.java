package com.sunshine.orchestrator.memory.stm;

import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.memory.MemoryProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StmWindowPolicyTest {

    @Test
    void selectWindow_respectsMaxMessages() {
        MemoryProperties.Stm stm = new MemoryProperties.Stm();
        stm.setMaxMessages(4);
        stm.setMaxChars(100_000);

        List<ChatTurn> loaded = IntStream.range(0, 8)
                .mapToObj(i -> new ChatTurn(i % 2 == 0 ? "user" : "assistant", "m" + i))
                .toList();

        List<ChatTurn> out = StmWindowPolicy.selectWindow(loaded, stm);
        assertThat(out).hasSize(4);
        assertThat(out.get(0).content()).isEqualTo("m4");
    }

    @Test
    void selectWindow_noContinuationKeywordStillReturnsWindow() {
        MemoryProperties.Stm stm = new MemoryProperties.Stm();
        stm.setMaxMessages(12);
        List<ChatTurn> loaded = List.of(
                new ChatTurn("user", "cpp"),
                new ChatTurn("assistant", "done"));
        assertThat(StmWindowPolicy.selectWindow(loaded, stm)).hasSize(2);
    }

    @Test
    void trimByChars_dropsOldestFirst() {
        List<ChatTurn> turns = List.of(
                new ChatTurn("user", "aaaa"),
                new ChatTurn("assistant", "bbbb"),
                new ChatTurn("user", "cc"));
        List<ChatTurn> out = StmWindowPolicy.trimByChars(turns, 6);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).content()).isEqualTo("bbbb");
    }
}
