package com.sunshine.orchestrator.catalog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Skill Catalog 兜底轮询刷新（30s）；启用/发布时优先靠 Redis 即时刷新 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "orchestrator.catalog", name = "refresh-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SkillCatalogRefreshScheduler {

    private final SkillCatalogService skillCatalogService;

    @Scheduled(fixedDelay = 30000)
    public void scheduledRefresh() {
        skillCatalogService.refresh();
    }
}
