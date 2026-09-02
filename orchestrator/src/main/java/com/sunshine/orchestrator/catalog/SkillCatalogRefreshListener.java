package com.sunshine.orchestrator.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** 订阅 skill-catalog-changed 热刷新 Catalog */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCatalogRefreshListener implements MessageListener {

    private final SkillCatalogService skillCatalogService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String tenantId = message != null && message.getBody() != null
                ? new String(message.getBody())
                : "default";
        log.info("[SkillCatalogRefreshListener] refresh triggered tenant={}", tenantId);
        skillCatalogService.refresh();
    }
}
