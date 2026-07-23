package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.client.StreamToken;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AS2 P1：AgentEvent（io.agentscope.core.event）→ StreamToken 纯映射单测。
 * 与 legacy {@code com.sunshine.orchestrator.agent.AgentScopeEventMapper}（io.agentscope.core.agent.Event）无关联。
 */
class AgentScopeEventMapperTest {

    private final AgentScopeEventMapper mapper = new AgentScopeEventMapper();

    @Test
    void mapsTextBlockDeltaToContent() {
        TextBlockDeltaEvent ev = new TextBlockDeltaEvent("reply-1", "block-1", "你好");
        List<StreamToken> out = mapper.mapAgentEvent(ev, "msg-1");
        assertEquals(1, out.size());
        assertEquals(StreamToken.KIND_CONTENT, out.get(0).kind());
        assertEquals("你好", out.get(0).text());
    }

    @Test
    void mapsThinkingBlockDeltaToReasoning() {
        ThinkingBlockDeltaEvent ev = new ThinkingBlockDeltaEvent("reply-1", "block-1", "思考一下");
        List<StreamToken> out = mapper.mapAgentEvent(ev, "msg-1");
        assertEquals(1, out.size());
        assertEquals(StreamToken.KIND_REASONING, out.get(0).kind());
        assertEquals("思考一下", out.get(0).text());
    }

    @Test
    void mapsToolCallStartToRunningStep() {
        ToolCallStartEvent ev = new ToolCallStartEvent("reply-1", "call-1", "search_knowledge");
        List<StreamToken> out = mapper.mapAgentEvent(ev, "msg-1");
        assertEquals(1, out.size());
        StreamToken token = out.get(0);
        assertEquals(StreamToken.KIND_STEP, token.kind());
        assertEquals("tool-call-1", token.step().id());
        assertEquals("tool", token.step().phase());
        assertEquals("running", token.step().lifecycle());
        assertEquals("search_knowledge", token.step().label());
    }

    @Test
    void mapsToolCallEndToDoneStep() {
        ToolCallEndEvent ev = new ToolCallEndEvent("reply-1", "call-1", "search_knowledge");
        List<StreamToken> out = mapper.mapAgentEvent(ev, "msg-1");
        assertEquals(1, out.size());
        StreamToken token = out.get(0);
        assertEquals(StreamToken.KIND_STEP, token.kind());
        assertEquals("tool-call-1", token.step().id());
        assertEquals("tool", token.step().phase());
        assertEquals("done", token.step().lifecycle());
        assertEquals("search_knowledge", token.step().label());
    }

    @Test
    void agentStartProducesNoTokens() {
        AgentStartEvent ev = new AgentStartEvent("session-1", "reply-1", "assistant");
        assertTrue(mapper.mapAgentEvent(ev, "msg-1").isEmpty());
    }

    @Test
    void agentEndProducesNoTokens() {
        AgentEndEvent ev = new AgentEndEvent("reply-1");
        assertTrue(mapper.mapAgentEvent(ev, "msg-1").isEmpty());
    }

    @Test
    void unmappedEventProducesNoTokens() {
        CustomEvent ev = new CustomEvent("some-custom");
        assertTrue(mapper.mapAgentEvent(ev, "msg-1").isEmpty());
    }
}
