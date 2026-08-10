package com.sunshine.orchestrator.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** 订阅 model-catalog-changed；刷新失败保留旧 snapshot */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCatalogRefreshListener implements MessageListener {

    private final ModelSceneResolver modelSceneResolver;
    private final ModelWindowCacheBridge modelWindowCacheBridge;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String tenantId = message != null && message.getBody() != null
                ? new String(message.getBody())
                : "default";
        log.info("[ModelCatalogRefreshListener] refresh triggered tenant={}", tenantId);
        modelSceneResolver.refreshBestEffort();
        modelWindowCacheBridge.syncFromResolver();
    }
}
