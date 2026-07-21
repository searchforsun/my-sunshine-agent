package com.sunshine.rag.admin.config;

import com.sunshine.rag.admin.config.dto.ConfigFieldSchema;
import com.sunshine.rag.admin.config.dto.ConfigSchemaResponse;
import com.sunshine.rag.admin.config.dto.ConfigScopeGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagConfigSchemaServiceTest {

    @Mock
    private ConfigVersionService configVersionService;
    @Mock
    private EffectiveConfigResolver effectiveConfigResolver;

    private RagConfigSchemaService schemaService;

    @BeforeEach
    void setUp() {
        Map<String, Object> payload = ConfigBundleTestFixtures.fullPayload();
        when(configVersionService.getEffective(org.mockito.ArgumentMatchers.eq("default"), org.mockito.ArgumentMatchers.eq("default"), org.mockito.ArgumentMatchers.eq("published"), org.mockito.ArgumentMatchers.eq(null)))
                .thenReturn(payload);
        when(effectiveConfigResolver.resolve(any(), any()))
                .thenReturn(ConfigBundlePayload.toResolvedKbConfig(payload));
        schemaService = new RagConfigSchemaService(configVersionService, effectiveConfigResolver);
    }

    @Test
    void getSchemaReturnsAllScopesWithCurrentValues() {
        ConfigSchemaResponse response = schemaService.getSchema("default", "default");
        assertThat(response.scopes()).hasSize(ConfigScope.values().length);
        ConfigScopeGroup searchScope = response.scopes().stream()
                .filter(group -> "rag-search".equals(group.scope()))
                .findFirst()
                .orElseThrow();
        ConfigFieldSchema minScore = searchScope.fields().stream()
                .filter(field -> "minScore".equals(field.fieldId()))
                .findFirst()
                .orElseThrow();
        assertThat(minScore.currentValue()).isEqualTo(0.48f);
        assertThat(minScore.scope()).isEqualTo("rag-search");
        assertThat(response.effective().minScore()).isEqualTo(0.48f);
    }
}

class ConfigDraftMergerTest {

    @org.junit.jupiter.api.Test
    void mergeAppliesSearchScope() {
        EffectiveRagConfig base = new EffectiveRagConfig(0.48f, "hybrid+rerank", 60, 20, 0.25f);
        EffectiveRagConfig merged = ConfigDraftMerger.merge(
                base, ConfigScope.RAG_SEARCH, java.util.Map.of("minScore", 0.55));
        org.assertj.core.api.Assertions.assertThat(merged.minScore()).isEqualTo(0.55f);
        org.assertj.core.api.Assertions.assertThat(merged.strategy()).isEqualTo("hybrid+rerank");
    }
}
