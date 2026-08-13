package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPreference;
import org.springframework.util.StringUtils;

/** 路由上下文：preference 过渡承载用户钉死的 executionMode；kind 正交透传 */
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

    public RoutingContext withUserMessage(String message) {
        return new RoutingContext(
                message, traceMessageId, preference, forcedWorkflowId, clientSkillId, memory, lockedMode, kind);
    }

    public RoutingContext withForcedWorkflowId(String workflowId) {
        return new RoutingContext(
                userMessage, traceMessageId, preference, workflowId, clientSkillId, memory, lockedMode, kind);
    }

    public RoutingContext withoutClientSkill() {
        return new RoutingContext(
                userMessage, traceMessageId, preference, forcedWorkflowId, null, memory, lockedMode, kind);
    }

    /** 会话形态；缺省 chat（四轴 kind，路由不改写） */
    public String kindOrDefault() {
        return StringUtils.hasText(kind) ? kind.strip() : "chat";
    }

    /** 用户钉死的执行模式（与 preference 同值；v6 无 auto 自判） */
    public ExecutionMode executionMode() {
        if (preference == null) {
            return ExecutionMode.FAST;
        }
        return ExecutionMode.from(preference.wireValue());
    }

    /** 收集用锁死 mode：显式 lockedMode 优先，否则取用户 preference */
    public ExecutionMode effectiveLockedMode() {
        return lockedMode != null ? lockedMode : executionMode();
    }

    /** 轨 A：fast / pro — 收集 skill + agent */
    public boolean isAgentSkillTrack() {
        ExecutionMode mode = effectiveLockedMode();
        return mode == ExecutionMode.FAST || mode == ExecutionMode.PRO;
    }

    /** 轨 B：仅 workflow */
    public boolean isWorkflowTrack() {
        return effectiveLockedMode() == ExecutionMode.WORKFLOW;
    }

    public boolean allowsSkillBinding() {
        return preference == null || preference.allowsSkillBinding();
    }
}
