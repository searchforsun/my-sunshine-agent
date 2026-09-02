package com.sunshine.model.event;

import com.sunshine.common.model.ModelCatalogChannels;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 模型 Catalog 变更 Redis 广播 */
@Component
@RequiredArgsConstructor
public class ModelCatalogChangePublisher {

    private final StringRedisTemplate redis;

    public void publish(String tenantId) {
        String payload = tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
        redis.convertAndSend(ModelCatalogChannels.CHANGED, payload);
    }
}
