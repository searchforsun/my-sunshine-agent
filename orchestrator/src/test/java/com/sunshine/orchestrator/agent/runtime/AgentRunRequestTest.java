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
    void resolveBridgeId_subUsesRunIdPrefix() {
        AgentRunRequest req = AgentRunRequest.sub(
                MemoryContext.empty(), "analyze", java.util.List.of("ctx block"), "u1", "default");
        assertThat(req.resolveBridgeId()).isEqualTo("sub-" + req.runId());
    }

    @Test
    void resolveBridgeId_mainUsesRunIdPrefix() {
        AgentRunRequest req = AgentRunRequest.main(
                MemoryContext.empty(), "hello", "u1", "default", "msg-1");
        assertThat(req.resolveBridgeId()).isEqualTo("main-" + req.runId());
    }

    @Test
    void resolveBridgeId_subWithAssistantMsgIdStillUsesSubPrefix() {
        AgentRunRequest req = AgentRunRequest.sub(
                MemoryContext.empty(),
                "审批",
                java.util.List.of("ctx"),
                "u1",
                "default",
                "msg-main",
                null,
                java.util.List.of("approve_oa_task"),
                null,
                0);
        assertThat(req.assistantMessageId()).isEqualTo("msg-main");
        assertThat(req.resolveBridgeId()).isEqualTo("sub-" + req.runId());
    }

    @Test
    void sub_withNodeParams_carriesSkillToolsOverlayAndMaxIters() {
        AgentRunRequest req = AgentRunRequest.sub(
                MemoryContext.empty(),
                "analyze",
                java.util.List.of("ctx"),
                "u1",
                "default",
                "finance-analysis",
                java.util.List.of("list_finance_messages"),
                "仅内部分析",
                4);
        assertThat(req.role()).isEqualTo(AgentRole.SUB);
        assertThat(req.skillId()).isEqualTo("finance-analysis");
        assertThat(req.toolWhitelist()).containsExactly("list_finance_messages");
        assertThat(req.systemOverlay()).isEqualTo("仅内部分析");
        assertThat(req.maxIters()).isEqualTo(4);
    }

    @Test
    void compactConstructor_normalizesNullMemoryAndBlocks() {
        AgentRunRequest req = new AgentRunRequest(
                AgentRole.MAIN, "run-1", null, null, "q", null,
                "u1", "default", "msg-1", null, null, null, 0, TimelineBinding.MAIN_FULL, false);
        assertThat(req.memory()).isEqualTo(MemoryContext.empty());
        assertThat(req.injectedBlocks()).isEmpty();
    }
}
