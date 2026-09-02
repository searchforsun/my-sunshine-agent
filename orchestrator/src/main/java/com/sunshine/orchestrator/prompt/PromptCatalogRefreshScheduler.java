package com.sunshine.orchestrator.prompt;

import com.sunshine.orchestrator.client.PromptCatalogClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 启动同步拉全量 Catalog（失败阻止 ready）；其后按 catalogVersion 热更新。
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "prompt-catalog", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class PromptCatalogRefreshScheduler {

    private final PromptCatalogClient promptCatalogClient;
    private final PromptCatalogHolder promptCatalogHolder;

    @PostConstruct
    void warmUp() {
        PromptCatalogSnapshot snapshot = promptCatalogClient.fetchSnapshot();
        promptCatalogHolder.replace(snapshot);
        log.info("[PromptCatalog] warm-up ok version={} rules={}",
                snapshot.catalogVersion(), snapshot.routingRules().size());
    }

    @Scheduled(fixedDelayString = "${prompt-catalog.refresh-ms:5000}")
    void scheduledRefresh() {
        // 先轻量比对版本，未变化直接跳过全量拉取；版本探测失败保留旧视图
        try {
            if (promptCatalogClient.fetchVersion() == promptCatalogHolder.snapshot().catalogVersion()) {
                return;
            }
        } catch (Exception e) {
            log.debug("[PromptCatalog] version check failed, keep previous: {}", e.getMessage());
            return;
        }
        boolean replaced = promptCatalogHolder.refreshSafely(promptCatalogClient::fetchSnapshot);
        if (replaced) {
            PromptCatalogSnapshot snap = promptCatalogHolder.snapshot();
            log.info("[PromptCatalog] refreshed version={} rules={}",
                    snap.catalogVersion(), snap.routingRules().size());
        }
    }
}
