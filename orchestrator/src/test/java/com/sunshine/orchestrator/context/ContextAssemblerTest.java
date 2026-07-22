package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAssemblerTest {

    private ContextProperties properties;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        properties = new ContextProperties();
        assembler = new ContextAssembler(properties);
    }

    @Test
    void assemble_keepsLastNearTurns() {
        properties.getL1().setNearTurns(2);
        properties.getL1().setMaxChars(100_000);
        List<ChatTurn> history = IntStream.range(0, 20)
                .mapToObj(i -> new ChatTurn(i % 2 == 0 ? "user" : "assistant", "m" + i))
                .toList();

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "current query"));

        assertThat(ctx.nearTurns()).hasSize(2);
        assertThat(ctx.nearTurns().get(0).content()).isEqualTo("m18");
        assertThat(ctx.nearTurns().get(1).content()).isEqualTo("m19");
        assertThat(ctx.midTurns()).isEmpty();
        assertThat(ctx.l2SystemBlock()).isBlank();
        assertThat(ctx.farSummaryBlock()).isBlank();
        assertThat(ctx.l3MaterialBlock()).isBlank();
    }

    @Test
    void assemble_dropsWholeTurnsFromHeadWhenOverMaxChars() {
        properties.getL1().setNearTurns(10);
        properties.getL1().setMaxChars(6);
        List<ChatTurn> history = List.of(
                new ChatTurn("user", "aaaa"),
                new ChatTurn("assistant", "bbbb"),
                new ChatTurn("user", "cc"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q"));

        assertThat(ctx.nearTurns()).hasSize(2);
        assertThat(ctx.nearTurns().get(0).content()).isEqualTo("bbbb");
        assertThat(ctx.nearTurns().get(1).content()).isEqualTo("cc");
    }

    @Test
    void assemble_doesNotTruncateSingleMessageContent() {
        properties.getL1().setNearTurns(4);
        properties.getL1().setMaxChars(100_000);
        String longReply = "x".repeat(2000);
        List<ChatTurn> history = List.of(
                new ChatTurn("user", "q"),
                new ChatTurn("assistant", longReply));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "follow up"));

        assertThat(ctx.nearTurns().get(1).content()).hasSize(2000);
    }

    @Test
    void assemble_filtersBlankTurns() {
        properties.getL1().setNearTurns(8);
        List<ChatTurn> history = List.of(
                new ChatTurn("user", "hello"),
                new ChatTurn("assistant", "  "));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "again"));

        assertThat(ctx.nearTurns()).hasSize(1);
        assertThat(ctx.nearTurns().get(0).role()).isEqualTo("user");
    }

    @Test
    void assemble_whenDisabled_returnsEmpty() {
        properties.setEnabled(false);
        List<ChatTurn> history = List.of(new ChatTurn("user", "hi"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "q"));

        assertThat(ctx.hasAnyLayer()).isFalse();
    }

    @Test
    void assemble_emptyHistory_returnsEmptyNear() {
        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", List.of(), "q"));

        assertThat(ctx.nearTurns()).isEmpty();
        assertThat(ctx.midTurns()).isEmpty();
        assertThat(ctx.l2SystemBlock()).isBlank();
    }

    @Test
    void assemble_nullHistory_returnsEmpty() {
        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", null, "q"));

        assertThat(ctx.hasAnyLayer()).isFalse();
    }

    @Test
    void assemble_historyWithinBudget_keepsAll() {
        properties.getL1().setNearTurns(8);
        properties.getL1().setMaxChars(100_000);
        List<ChatTurn> history = new ArrayList<>();
        history.add(new ChatTurn("user", "写 cpp 快排"));
        history.add(new ChatTurn("assistant", "cpp code full content"));

        AssembledContext ctx = assembler.assemble(new ContextAssembler.AssembleRequest(
                "u1", "default", "c1", history, "写 py 快排"));

        assertThat(ctx.nearTurns()).hasSize(2);
        assertThat(ctx.nearTurns().get(0).content()).isEqualTo("写 cpp 快排");
        assertThat(ctx.nearTurns().get(1).content()).isEqualTo("cpp code full content");
    }
}
