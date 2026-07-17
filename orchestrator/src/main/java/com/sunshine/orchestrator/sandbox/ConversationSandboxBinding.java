package com.sunshine.orchestrator.sandbox;

import java.util.List;

/** Redis 中对话级沙箱绑定 — 多 Skill 累计挂载 */
public record ConversationSandboxBinding(
        String sessionId,
        List<String> loadedSkillIds,
        String userId,
        String tenantId,
        String conversationId) {

    public ConversationSandboxBinding {
        loadedSkillIds = loadedSkillIds != null ? List.copyOf(loadedSkillIds) : List.of();
    }

    public boolean hasSkill(String skillId) {
        return skillId != null && loadedSkillIds.contains(skillId);
    }
}
