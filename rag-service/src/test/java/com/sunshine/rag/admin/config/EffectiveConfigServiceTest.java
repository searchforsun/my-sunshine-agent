package com.sunshine.rag.admin.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectiveConfigServiceTest {

    @Mock
    private EffectiveConfigResolver effectiveConfigResolver;

    private EffectiveConfigService service;

    @BeforeEach
    void setUp() {
        service = new EffectiveConfigService(effectiveConfigResolver);
    }

    @Test
    void resolveDelegatesToResolverProduction() {
        EffectiveRagConfig expected = new EffectiveRagConfig(0.55f, "vector", 60, 20, 0.25f, 1200);
        when(effectiveConfigResolver.resolve("default", "finance"))
                .thenReturn(ConfigBundlePayload.toResolvedKbConfig(ConfigBundleTestFixtures.fullPayload(0.55f, "vector")));
        EffectiveRagConfig config = service.resolve("default", "finance");
        assertThat(config.minScore()).isEqualTo(0.55f);
        assertThat(config.strategy()).isEqualTo("vector");
    }
}
