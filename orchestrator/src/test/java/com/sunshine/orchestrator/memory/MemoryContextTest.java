package com.sunshine.orchestrator.memory;

import com.sunshine.orchestrator.conversation.ChatTurn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 旧 MemoryContext 仍被 MemoryMessageBuilder 使用，直至 Task 8 删除。 */
class MemoryContextTest {

    @Test
    void forSubAgent_hasNoMemoryLayers() {
        MemoryContext ctx = MemoryContext.forSubAgent();
        assertThat(ctx).isEqualTo(MemoryContext.empty());
        assertThat(ctx.ltmSnippet()).isBlank();
        assertThat(ctx.mtmSnippet()).isBlank();
        assertThat(ctx.stmTurns()).isEmpty();
        assertThat(ctx.hasAnyLayer()).isFalse();
    }

    @Test
    void empty_differsFromFullMemory() {
        MemoryContext full = new MemoryContext("ltm", "mtm", List.of(new ChatTurn("user", "q")));
        assertThat(MemoryContext.forSubAgent()).isNotEqualTo(full);
        assertThat(full.hasAnyLayer()).isTrue();
    }
}
