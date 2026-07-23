package com.sunshine.orchestrator.agent.runtime;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ToolStepIds;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AS2 P1：io.agentscope.core.event.AgentEvent → StreamToken 纯翻译层（禁截断/摘要模型输出）。
 * 与 legacy {@code com.sunshine.orchestrator.agent.AgentScopeEventMapper}（旧 io.agentscope.core.agent.Event）并存，
 * 旧路径 P7 前保持不动。P1-2 在 ReActAgentRuntime 中接线调用。
 *
 * 映射规则：
 * <ul>
 *   <li>{@link TextBlockDeltaEvent} → {@code content(delta)}（正文增量原样透传）</li>
 *   <li>{@link ThinkingBlockDeltaEvent} → {@code reasoning(delta)}（推理增量原样透传）</li>
 *   <li>{@link ToolCallStartEvent} → {@code step(ProcessingStep.running)}，步骤 id=tool-{catalogToolName}@{epochMs}（SSOT {@code ToolStepIds.forInvocation}；start/end 同 toolCallId 共享同一 id，保证 {@code ProcessingStepMerger} 按全 id upsert 不串号）</li>
 *   <li>{@link ToolCallEndEvent} → {@code step(ProcessingStep.done)}，生命周期收口</li>
 *   <li>其余事件（agent start/end、block 生命周期、tool result、确认类）→ {@code List.of()}，由 P1-2 接线时按需扩展</li>
 * </ul>
 */
public final class AgentScopeEventMapper {

    /** toolCallId → 步骤 id，保证同一 tool call 的 start/end 共享同一 id */
    private final Map<String, String> toolCallStepIds = new ConcurrentHashMap<>();

    /**
     * @param messageId reserved for P1-2 StepEventBridge binding
     */
    public List<StreamToken> mapAgentEvent(AgentEvent ev, String messageId) {
        if (ev instanceof TextBlockDeltaEvent d) {
            return List.of(StreamToken.content(d.getDelta()));
        }
        if (ev instanceof ThinkingBlockDeltaEvent t) {
            return List.of(StreamToken.reasoning(t.getDelta()));
        }
        if (ev instanceof ToolCallStartEvent s) {
            String stepId = toolStepIdOnStart(s.getToolCallName(), s.getToolCallId());
            if (stepId == null) {
                return List.of();
            }
            return List.of(StreamToken.step(
                    ProcessingStep.running(stepId, "tool", s.getToolCallName())));
        }
        if (ev instanceof ToolCallEndEvent e) {
            String stepId = toolStepIdOnEnd(e.getToolCallName(), e.getToolCallId());
            if (stepId == null) {
                return List.of();
            }
            return List.of(StreamToken.step(
                    ProcessingStep.done(stepId, "tool", e.getToolCallName(), null)));
        }
        return List.of();
    }

    /** start 事件：生成并缓存步骤 id */
    private String toolStepIdOnStart(String toolName, String toolCallId) {
        if (toolName == null || toolName.isBlank() || toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        String stepId = ToolStepIds.forInvocation("tool-" + toolName, System.currentTimeMillis());
        toolCallStepIds.put(toolCallId, stepId);
        return stepId;
    }

    /** end 事件：复用 start 缓存的步骤 id；若无缓存则按当前时刻生成（兼容孤立 end） */
    private String toolStepIdOnEnd(String toolName, String toolCallId) {
        if (toolName == null || toolName.isBlank() || toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        String cached = toolCallStepIds.remove(toolCallId);
        if (cached != null) {
            return cached;
        }
        return ToolStepIds.forInvocation("tool-" + toolName, System.currentTimeMillis());
    }
}
