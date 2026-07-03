package com.sunshine.rag.admin.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectiveConfigResolverTest {

    @Mock
    private ConfigVersionService configVersionService;

    private EffectiveConfigResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new EffectiveConfigResolver(configVersionService);
    }

    @Test
    void resolveProduction_usesPublishedPayload() {
        Map<String, Object> payload = ConfigBundleTestFixtures.fullPayload(0.55f, "vector");
        when(configVersionService.getEffective("default", "finance", "published", null)).thenReturn(payload);
        ResolvedKbConfig resolved = resolver.resolve("default", "finance");
        assertThat(resolved.retrieval().minScore()).isEqualTo(0.55f);
        assertThat(resolved.retrieval().strategy()).isEqualTo("vector");
    }

    @Test
    void invalidate_clearsProductionCache() {
        Map<String, Object> payloadA = new LinkedHashMap<>(ConfigBundleTestFixtures.fullPayload(0.55f, "vector"));
        Map<String, Object> payloadB = ConfigBundleTestFixtures.fullPayload(0.60f, "hybrid");
        when(configVersionService.getEffective("default", "kb-a", "published", null)).thenReturn(payloadA);
        when(configVersionService.getEffective("default", "kb-b", "published", null)).thenReturn(payloadB);
        assertThat(resolver.resolve("default", "kb-a").retrieval().minScore()).isEqualTo(0.55f);
        assertThat(resolver.resolve("default", "kb-b").retrieval().minScore()).isEqualTo(0.60f);
        payloadA.put("search", Map.of(
                "minScore", 0.99,
                "strategy", "vector",
                "rrfK", 60,
                "hybridPoolSize", 20,
                "defaultTopK", 3));
        assertThat(resolver.resolve("default", "kb-a").retrieval().minScore()).isEqualTo(0.55f);
        resolver.invalidate("default", "kb-a");
        assertThat(resolver.resolve("default", "kb-a").retrieval().minScore()).isEqualTo(0.99f);
        assertThat(resolver.resolve("default", "kb-b").retrieval().minScore()).isEqualTo(0.60f);
        verify(configVersionService, org.mockito.Mockito.times(2))
                .getEffective(eq("default"), eq("kb-a"), eq("published"), eq(null));
    }
}
