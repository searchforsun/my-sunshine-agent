package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AssembledContextTest {
    @Test
    void empty_hasNoLayers() {
        assertThat(AssembledContext.empty().hasAnyLayer()).isFalse();
    }

    @Test
    void forSubAgent_isEmpty() {
        assertThat(AssembledContext.forSubAgent()).isEqualTo(AssembledContext.empty());
    }

    @Test
    void hasAnyLayer_trueWhenNearPresent() {
        var ctx = new AssembledContext("", "", List.of(), List.of(new ChatTurn("user", "hi")), "");
        assertThat(ctx.hasAnyLayer()).isTrue();
    }
}
