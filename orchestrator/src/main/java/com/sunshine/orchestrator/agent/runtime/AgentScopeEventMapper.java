package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;

import java.util.List;

/**
 * AS2 P1：io.agentscope.core.event.AgentEvent → StreamToken 纯翻译层（禁截断/摘要模型输出）。
 * 与 legacy {@code com.sunshine.orchestrator.agent.AgentScopeEventMapper}（旧 io.agentscope.core.agent.Event）并存，
 * 旧路径 P7 前保持不动。P1-2 在 ReActAgentRuntime 中接线调用。
 *
 * 映射规则：
 * <ul>
 *   <li>{@link TextBlockDeltaEvent} → {@code content(delta)}（正文增量原样透传）</li>
 *   <li>{@link ThinkingBlockDeltaEvent} → {@code reasoning(delta)}（推理增量原样透传）</li>
 *   <li>{@link ToolCallStartEvent} → {@code step(ProcessingStep.running)}，步骤 id=tool-{toolCallId}</li>
 *   <li>{@link ToolCallEndEvent} → {@code step(ProcessingStep.done)}，生命周期收口</li>
 *   <li>其余事件（agent start/end、block 生命周期、tool result、确认类）→ {@code List.of()}，由 P1-2 接线时按需扩展</li>
 * </ul>
 */
public final class AgentScopeEventMapper {

    public List<StreamToken> mapAgentEvent(AgentEvent ev, String messageId) {
        if (ev instanceof TextBlockDeltaEvent d) {
            return List.of(StreamToken.content(d.getDelta()));
        }
        if (ev instanceof ThinkingBlockDeltaEvent t) {
            return List.of(StreamToken.reasoning(t.getDelta()));
        }
        if (ev instanceof ToolCallStartEvent s) {
            return List.of(StreamToken.step(
                    ProcessingStep.running(toolStepId(s.getToolCallId()), "tool", s.getToolCallName())));
        }
        if (ev instanceof ToolCallEndEvent e) {
            return List.of(StreamToken.step(
                    ProcessingStep.done(toolStepId(e.getToolCallId()), "tool", e.getToolCallName(), null)));
        }
        return List.of();
    }

    private static String toolStepId(String toolCallId) {
        return "tool-" + (toolCallId != null ? toolCallId : "unknown");
    }
}
