package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.memory.MemoryContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunRequestTest {

    @Test
    void main_bindsRoleTimelineAndAssistantMsgId() {
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "hello", "u1", "default", "msg-1");
        assertThat(req.role()).isEqualTo(AgentRole.MAIN);
        assertThat(req.assistantMessageId()).isEqualTo("msg-1");
        assertThat(req.timeline()).isEqualTo(TimelineBinding.MAIN_FULL);
        assertThat(req.runId()).isNotBlank();
        assertThat(req.injectedBlocks()).isEmpty();
    }

    @Test
    void main_carriesInjectedBlocks() {
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "q", "u1", "default", "msg-1",
                java.util.List.of("rag ctx", "finance ctx"));
        assertThat(req.injectedBlocks()).containsExactly("rag ctx", "finance ctx");
    }

    @Test
    void sub_noAssistantMsgIdAndCompressedTimeline() {
        AgentRunRequest req = AgentRunRequest.sub(
                MemoryContext.empty(), "analyze", java.util.List.of("ctx block"), "u1", "default");
        assertThat(req.role()).isEqualTo(AgentRole.SUB);
        assertThat(req.assistantMessageId()).isNull();
        assertThat(req.timeline()).isEqualTo(TimelineBinding.SUB_COMPRESSED);
        assertThat(req.injectedBlocks()).containsExactly("ctx block");
    }

    @Test
    void compactConstructor_normalizesNullMemoryAndBlocks() {
        AgentRunRequest req = new AgentRunRequest(
                AgentRole.MAIN, "run-1", null, null, "q", null,
                "u1", "default", "msg-1", null, null, null, 0, TimelineBinding.MAIN_FULL);
        assertThat(req.memory()).isEqualTo(MemoryContext.empty());
        assertThat(req.injectedBlocks()).isEmpty();
    }
}
