package com.sunshine.orchestrator.config;

import com.sunshine.orchestrator.catalog.WorkflowCatalogRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/** 订阅 workflow-catalog-changed 热刷新 Catalog */
@Slf4j
@Component
public class WorkflowCatalogRefreshListener implements MessageListener {

    private final WorkflowCatalogRegistry catalogRegistry;

    public WorkflowCatalogRefreshListener(WorkflowCatalogRegistry catalogRegistry) {
        this.catalogRegistry = catalogRegistry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info("[WorkflowCatalogRefreshListener] refresh tenant={}", new String(message.getBody()));
        catalogRegistry.refresh();
    }
}
