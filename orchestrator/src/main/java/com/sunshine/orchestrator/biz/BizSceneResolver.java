package com.sunshine.orchestrator.biz;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * biz_scene 解析（authority §2.1 · K2）：agent 优先非空 → 否则 skillIds 第一非空 → 否则 null。
 * 非空码必须落在业务场景 Lab 且 active（闭集）；disabled/未知码视为无效返回空并记 audit 日志。
 */
@Slf4j
public final class BizSceneResolver {

    private BizSceneResolver() {
    }

    public record SceneTagged(String id, String bizScene) {
    }

    public static Optional<String> resolve(
            List<SceneTagged> agents,
            List<SceneTagged> skills,
            Set<String> activeSceneCodes) {
        for (SceneTagged agent : emptyIfNull(agents)) {
            if (hasScene(agent.bizScene())) {
                return validate(agent, activeSceneCodes);
            }
        }
        for (SceneTagged skill : emptyIfNull(skills)) {
            if (hasScene(skill.bizScene())) {
                return validate(skill, activeSceneCodes);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validate(SceneTagged resource, Set<String> activeSceneCodes) {
        if (activeSceneCodes != null && activeSceneCodes.contains(resource.bizScene().strip())) {
            return Optional.of(resource.bizScene().strip());
        }
        log.warn("[BizScene] 资源 {} 的 biz_scene={} 不在 active Lab 码表，视为无效", resource.id(), resource.bizScene());
        return Optional.empty();
    }

    private static boolean hasScene(String bizScene) {
        return StringUtils.hasText(bizScene);
    }

    private static List<SceneTagged> emptyIfNull(List<SceneTagged> list) {
        return list != null ? list : List.of();
    }
}
