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
    private EffectiveConfigService effectiveConfigService;

    @BeforeEach
    void setUp() {
        Map<String, Object> payload = ConfigBundleTestFixtures.fullPayload();
        when(configVersionService.getEffective(org.mockito.ArgumentMatchers.eq("default"), org.mockito.ArgumentMatchers.eq("default"), org.mockito.ArgumentMatchers.eq("published"), org.mockito.ArgumentMatchers.eq(null)))
                .thenReturn(payload);
        when(effectiveConfigResolver.resolve(any(), any()))
                .thenReturn(ConfigBundlePayload.toResolvedKbConfig(payload));
        effectiveConfigService = new EffectiveConfigService(effectiveConfigResolver);
        schemaService = new RagConfigSchemaService(configVersionService, effectiveConfigService);
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
        assertThat(response.effective().chunkMaxSize()).isEqualTo(1200);
    }

    @Test
    void chunkScopeUsesPublishedPayload() {
        ConfigScopeGroup chunkScope = schemaService.getSchema("default", null).scopes().stream()
                .filter(group -> "rag-chunk".equals(group.scope()))
                .findFirst()
                .orElseThrow();
        assertThat(chunkScope.fields()).extracting(ConfigFieldSchema::fieldId).containsExactly("maxSize");
        assertThat(chunkScope.fields().getFirst().currentValue()).isEqualTo(1200);
    }
}

class ConfigDraftMergerTest {

    @org.junit.jupiter.api.Test
    void mergeAppliesSearchScope() {
        EffectiveRagConfig base = new EffectiveRagConfig(0.48f, "hybrid+rerank", 60, 20, 0.25f, 1200);
        EffectiveRagConfig merged = ConfigDraftMerger.merge(
                base, ConfigScope.RAG_SEARCH, java.util.Map.of("minScore", 0.55));
        org.assertj.core.api.Assertions.assertThat(merged.minScore()).isEqualTo(0.55f);
        org.assertj.core.api.Assertions.assertThat(merged.strategy()).isEqualTo("hybrid+rerank");
    }
}

@ExtendWith(MockitoExtension.class)
class ConfigDraftServiceTest {

    @Mock
    private com.sunshine.rag.repository.ConfigDraftRepository draftRepository;

    private ConfigDraftService draftService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        draftService = new ConfigDraftService(draftRepository, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @org.junit.jupiter.api.Test
    void saveDraftUpsertsDraftRow() {
        when(draftRepository.findFirstByTenantIdAndScopeAndStatusOrderByCreatedAtDesc("default", "rag-search", "draft"))
                .thenReturn(java.util.Optional.empty());
        when(draftRepository.save(any(com.sunshine.rag.entity.ConfigDraftEntity.class))).thenAnswer(invocation -> {
            com.sunshine.rag.entity.ConfigDraftEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            return entity;
        });
        var summary = draftService.saveDraft(
                "default", "rag-search", java.util.Map.of("minScore", 0.5), "u1");
        org.assertj.core.api.Assertions.assertThat(summary.scope()).isEqualTo("rag-search");
        org.assertj.core.api.Assertions.assertThat(summary.payload()).containsEntry("minScore", 0.5);
        org.mockito.ArgumentCaptor<com.sunshine.rag.entity.ConfigDraftEntity> captor =
                org.mockito.ArgumentCaptor.forClass(com.sunshine.rag.entity.ConfigDraftEntity.class);
        verify(draftRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getStatus()).isEqualTo("draft");
    }

    @org.junit.jupiter.api.Test
    void saveDraftRejectsUnknownScope() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> draftService.saveDraft("default", "bad-scope", java.util.Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知 scope");
    }

    @org.junit.jupiter.api.Test
    void listDraftsReturnsTenantDrafts() {
        com.sunshine.rag.entity.ConfigDraftEntity entity = new com.sunshine.rag.entity.ConfigDraftEntity();
        entity.setId(2L);
        entity.setTenantId("default");
        entity.setScope("rag-chunk");
        entity.setPayloadJson("{\"maxSize\":900}");
        entity.setStatus("draft");
        when(draftRepository.findByTenantIdAndStatusOrderByCreatedAtDesc("default", "draft"))
                .thenReturn(java.util.List.of(entity));
        var drafts = draftService.listDrafts("default");
        org.assertj.core.api.Assertions.assertThat(drafts).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(drafts.getFirst().payload()).containsEntry("maxSize", 900);
    }
}
