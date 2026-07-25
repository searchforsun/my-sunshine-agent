package com.sunshine.orchestrator.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ReAct 原生断点续传：cancel 时 interrupt + 手动 saveAgentState 持久化到 stateStore，
 * 续跑时通过 {@code streamEvents(inputs, RuntimeContext)} 从 stateStore 恢复。
 * <p>
 * AgentScope 2.0 的 streamEvents 不会自动 save AgentState，须在 interrupt 时手动调
 * {@code ReActAgent.saveAgentState(userId, sessionId)} 保存 checkpoint。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactCheckpointService {

    private final AgentStateStore stateStore;

    public boolean hasCheckpoint(String userId, String assistantMessageId) {
        return stateStore.exists(userId, assistantMessageId);
    }

    public void interrupt(HarnessAgent agent, String userId, String assistantMessageId) {
        log.info("[ReactCheckpointService] interrupt userId={} msg={}", userId, assistantMessageId);
        agent.interrupt();
        saveCheckpoint(agent, userId, assistantMessageId);
    }

    private void saveCheckpoint(HarnessAgent agent, String userId, String sessionId) {
        try {
            ReActAgent delegate = agent.getDelegate();
            delegate.saveAgentState(userId, sessionId);
            log.info("[ReactCheckpointService] checkpoint saved userId={} sessionId={}", userId, sessionId);
        } catch (Exception e) {
            log.warn("[ReactCheckpointService] saveAgentState failed userId={} sessionId={}: {}",
                    userId, sessionId, e.getMessage());
        }
    }

    public RuntimeContext resumeCtx(String userId, String assistantMessageId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(assistantMessageId)
                .build();
    }

    public void deleteCheckpoint(String userId, String assistantMessageId) {
        stateStore.delete(userId, assistantMessageId);
    }
}
