package com.sunshine.rag.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.config.RagChunkProperties;
import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.entity.KbConfigOverrideEntity;
import com.sunshine.rag.repository.KbConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectiveConfigServiceTest {

    @Mock
    private KbConfigOverrideRepository overrideRepository;

    private EffectiveConfigService service;

    @BeforeEach
    void setUp() {
        RagSearchProperties search = new RagSearchProperties();
        search.setMinScore(0.48f);
        search.setStrategy("hybrid+rerank");
        search.setRrfK(60);
        search.setHybridPoolSize(20);
        RagRerankProperties rerank = new RagRerankProperties();
        rerank.setMinScore(0.25f);
        RagChunkProperties chunk = new RagChunkProperties();
        chunk.setMaxSize(1200);
        service = new EffectiveConfigService(search, rerank, chunk, overrideRepository, new ObjectMapper());
    }

    @Test
    void resolveUsesNacosDefaultsWhenNoOverride() {
        when(overrideRepository.findByTenantIdAndKbId("default", "default")).thenReturn(Optional.empty());
        EffectiveRagConfig config = service.resolve("default", "default");
        assertThat(config.minScore()).isEqualTo(0.48f);
        assertThat(config.strategy()).isEqualTo("hybrid+rerank");
        assertThat(config.chunkMaxSize()).isEqualTo(1200);
    }

    @Test
    void resolveMergesKbOverride() {
        KbConfigOverrideEntity entity = new KbConfigOverrideEntity();
        entity.setTenantId("default");
        entity.setKbId("finance");
        entity.setOverrideJson("{\"minScore\":0.55,\"strategy\":\"vector\"}");
        when(overrideRepository.findByTenantIdAndKbId("default", "finance")).thenReturn(Optional.of(entity));
        EffectiveRagConfig config = service.resolve("default", "finance");
        assertThat(config.minScore()).isEqualTo(0.55f);
        assertThat(config.strategy()).isEqualTo("vector");
        assertThat(config.rrfK()).isEqualTo(60);
    }
}
