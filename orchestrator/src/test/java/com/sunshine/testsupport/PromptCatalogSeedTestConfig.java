package com.sunshine.testsupport;

import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.RoutingCatalogFixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Spring Boot 测试：prompt-catalog.enabled=false 时预装 T0 种子路由规则。
 */
@TestConfiguration
public class PromptCatalogSeedTestConfig {

    @Autowired
    void warmUp(PromptCatalogHolder holder) {
        holder.replace(RoutingCatalogFixtures.seedSnapshot());
    }
}
