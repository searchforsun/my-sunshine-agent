package com.sunshine.orchestrator.execution;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plan-Workflow 用户暂停：按 assistant 消息跟踪当前节点与已提交上下文 */
@Service
public class WorkflowPauseService {

    private static final class RunState {
        final AtomicBoolean pauseRequested = new AtomicBoolean();
        volatile String planId;
        volatile String currentNodeId;
        volatile String committedCtxJson = "{}";
    }

    private final ConcurrentHashMap<String, RunState> byMessage = new ConcurrentHashMap<>();

    public void bindRun(String messageId, String planId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        RunState state = byMessage.computeIfAbsent(messageId, id -> new RunState());
        state.planId = planId;
    }

    public void setCurrentNode(String messageId, String nodeId) {
        RunState state = byMessage.get(messageId);
        if (state != null) {
            state.currentNodeId = nodeId;
        }
    }

    public void commitContext(String messageId, WorkflowContext wfCtx) {
        RunState state = byMessage.get(messageId);
        if (state != null && wfCtx != null) {
            state.committedCtxJson = WorkflowContextCodec.toJson(wfCtx);
        }
    }

    public void requestPause(String messageId) {
        RunState state = byMessage.get(messageId);
        if (state != null) {
            state.pauseRequested.set(true);
        }
    }

    /** 节点开始前消费一次暂停请求 */
    public boolean consumePauseRequested(String messageId) {
        RunState state = byMessage.get(messageId);
        return state != null && state.pauseRequested.compareAndSet(true, false);
    }

    public String getCurrentNodeId(String messageId) {
        RunState state = byMessage.get(messageId);
        return state != null ? state.currentNodeId : null;
    }

    public String getCommittedContextJson(String messageId) {
        RunState state = byMessage.get(messageId);
        return state != null ? state.committedCtxJson : "{}";
    }

    public void clearRun(String messageId) {
        if (messageId != null) {
            byMessage.remove(messageId);
        }
    }
}
