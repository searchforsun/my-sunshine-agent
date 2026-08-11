package com.sunshine.skill.event;

import com.sunshine.common.skill.SkillCatalogChannels;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Skill Catalog 变更 Redis 广播 */
@Component
@RequiredArgsConstructor
public class SkillCatalogChangePublisher {

    private final StringRedisTemplate redis;

    public void publish(String tenantId) {
        String payload = tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
        redis.convertAndSend(SkillCatalogChannels.CHANGED, payload);
    }
}
