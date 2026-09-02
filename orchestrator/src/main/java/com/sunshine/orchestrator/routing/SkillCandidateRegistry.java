package com.sunshine.orchestrator.routing;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S-C 候选 skill 集消息级注册表：本轮路由采纳出的候选（candidate &lt; 置信 ≤ trigger）
 * 按 assistantMessageId 承载，供 {@code sunshine_search_skills} 元工具校验与动态加载。
 * 与 {@code DecisionResumeSteps} 同为消息级静态注册表范式；run 结束经 remove 清理。
 */
public final class SkillCandidateRegistry {

    private static final ConcurrentHashMap<String, List<String>> BY_MESSAGE = new ConcurrentHashMap<>();

    private SkillCandidateRegistry() {
    }

    public static void bind(String messageId, List<String> candidateSkillIds) {
        if (messageId == null || messageId.isBlank()
                || candidateSkillIds == null || candidateSkillIds.isEmpty()) {
            return;
        }
        BY_MESSAGE.put(messageId.strip(), List.copyOf(candidateSkillIds));
    }

    public static List<String> candidates(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return List.of();
        }
        List<String> candidates = BY_MESSAGE.get(messageId.strip());
        return candidates != null ? candidates : List.of();
    }

    public static void remove(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        BY_MESSAGE.remove(messageId.strip());
    }
}
