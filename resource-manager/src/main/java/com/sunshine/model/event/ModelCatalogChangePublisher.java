package com.sunshine.model.event;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 模型 Catalog 变更 Redis 广播 */
@Component
@RequiredArgsConstructor
public class ModelCatalogChangePublisher {

    public static final String CHANNEL = "model-catalog-changed";

    private final StringRedisTemplate redis;

    public void publish(String tenantId) {
        String payload = tenantId == null || tenantId.isBlank() ? "default" : tenantId.strip();
        redis.convertAndSend(CHANNEL, payload);
    }
}
