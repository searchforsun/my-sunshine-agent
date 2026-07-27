package com.sunshine.orchestrator.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ReAct 原生断点续传的 checkpoint 查询/清理。续跑经 {@code streamEvents(inputs, RuntimeContext)}
 * 从 stateStore 恢复；checkpoint 的实际保存在 {@code ReActAgentRuntime.doFinally}（CANCEL/ON_ERROR
 * 时 {@code ReActAgent.saveAgentState}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactCheckpointService {

    private final AgentStateStore stateStore;

    public boolean hasCheckpoint(String userId, String assistantMessageId) {
        return stateStore.exists(userId, assistantMessageId);
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
