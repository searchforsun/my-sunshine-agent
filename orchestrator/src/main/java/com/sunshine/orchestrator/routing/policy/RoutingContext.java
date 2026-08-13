package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPreference;

/** 路由上下文：preference 过渡承载用户钉死的 executionMode */
public record RoutingContext(
        String userMessage,
        String traceMessageId,
        ExecutionPreference preference,
        String forcedWorkflowId,
        String clientSkillId,
        AssembledContext memory,
        /** L3 锁死 mode，仅解析绑定 */
        ExecutionMode lockedMode,
        /** 会话 kind（chat/…）；路由不改写，缺省 chat */
        String kind) {

    public RoutingContext(String userMessage, String traceMessageId) {
        this(userMessage, traceMessageId, ExecutionPreference.FAST, null, null, null, null, null);
    }

    public RoutingContext(
            String userMessage,
            String traceMessageId,
            ExecutionPreference preference,
            String forcedWorkflowId,
            String clientSkillId) {
        this(userMessage, traceMessageId, preference, forcedWorkflowId, clientSkillId, null, null, null);
    }

    public RoutingContext(
            String userMessage,
            String traceMessageId,
            ExecutionPreference preference,
            String forcedWorkflowId,
            String clientSkillId,
            AssembledContext memory) {
        this(userMessage, traceMessageId, preference, forcedWorkflowId, clientSkillId, memory, null, null);
    }

    public RoutingContext(
            String userMessage,
            String traceMessageId,
            ExecutionPreference preference,
            String forcedWorkflowId,
            String clientSkillId,
            AssembledContext memory,
            ExecutionMode lockedMode) {
        this(userMessage, traceMessageId, preference, forcedWorkflowId, clientSkillId, memory, lockedMode, null);
    }

    public static RoutingContext of(String userMessage) {
        return new RoutingContext(userMessage, null, ExecutionPreference.FAST, null, null, null, null, null);
    }

    public RoutingContext withLockedMode(ExecutionMode mode) {
        return new RoutingContext(
                userMessage, traceMessageId, preference, forcedWorkflowId, clientSkillId, memory, mode, kind);
    }

    /** 用户钉死的执行模式（与 preference 同值；v6 无 auto 自判） */
    public ExecutionMode executionMode() {
        if (preference == null) {
            return ExecutionMode.FAST;
        }
        return ExecutionMode.from(preference.wireValue());
    }

    public boolean allowsSkillBinding() {
        return preference == null || preference.allowsSkillBinding();
    }
}
