package com.sunshine.orchestrator.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Catalog 兜底轮询刷新（30s） */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "orchestrator.catalog", name = "refresh-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ToolCatalogRefreshScheduler {

    private final ToolCatalogService toolCatalogService;

    @Scheduled(fixedDelay = 30000)
    public void scheduledRefresh() {
        toolCatalogService.refresh();
    }
}
