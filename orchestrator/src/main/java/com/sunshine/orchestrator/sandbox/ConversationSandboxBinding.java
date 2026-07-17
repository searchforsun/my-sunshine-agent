package com.sunshine.orchestrator.sandbox;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Redis 中对话级沙箱绑定 — 多 Skill 累计挂载；双层生命周期 state/purgeAt */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConversationSandboxBinding(
        String sessionId,
        List<String> loadedSkillIds,
        String userId,
        String tenantId,
        String conversationId,
        String state,
        Long purgeAtEpochMs) {

    public static final String STATE_RUNNING = "running";
    public static final String STATE_STOPPED = "stopped";

    public ConversationSandboxBinding {
        loadedSkillIds = loadedSkillIds != null ? List.copyOf(loadedSkillIds) : List.of();
        if (state == null || state.isBlank()) {
            state = STATE_RUNNING;
        }
    }

    /** 兼容旧 5 字段构造 / 测试 */
    public ConversationSandboxBinding(
            String sessionId,
            List<String> loadedSkillIds,
            String userId,
            String tenantId,
            String conversationId) {
        this(sessionId, loadedSkillIds, userId, tenantId, conversationId, STATE_RUNNING, null);
    }

    public boolean hasSkill(String skillId) {
        return skillId != null && loadedSkillIds.contains(skillId);
    }

    @JsonIgnore
    public boolean isStopped() {
        return STATE_STOPPED.equalsIgnoreCase(state);
    }

    public ConversationSandboxBinding withState(String newState) {
        return new ConversationSandboxBinding(
                sessionId, loadedSkillIds, userId, tenantId, conversationId, newState, purgeAtEpochMs);
    }

    public ConversationSandboxBinding withPurgeAt(Long purgeAt) {
        return new ConversationSandboxBinding(
                sessionId, loadedSkillIds, userId, tenantId, conversationId, state, purgeAt);
    }

    public ConversationSandboxBinding withSkills(List<String> skills) {
        return new ConversationSandboxBinding(
                sessionId, skills, userId, tenantId, conversationId, state, purgeAtEpochMs);
    }
}
