package com.sunshine.orchestrator.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** 订阅 tool-catalog-changed 热刷新 Catalog */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCatalogRefreshListener implements MessageListener {

    private final ToolCatalogService toolCatalogService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String tenantId = message != null && message.getBody() != null
                ? new String(message.getBody())
                : "default";
        log.debug("[ToolCatalogRefreshListener] refresh triggered tenant={}", tenantId);
        toolCatalogService.refresh();
    }
}
