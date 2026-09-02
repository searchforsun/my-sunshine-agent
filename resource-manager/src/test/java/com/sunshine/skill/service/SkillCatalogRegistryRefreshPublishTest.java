package com.sunshine.skill.service;

import com.sunshine.skill.event.SkillCatalogChangePublisher;
import com.sunshine.skill.repo.SkillDefinitionRepository;
import com.sunshine.skill.repo.SkillVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 本地 Catalog refresh 后必须广播，供 orchestrator 热更新 */
@ExtendWith(MockitoExtension.class)
class SkillCatalogRegistryRefreshPublishTest {

    @Mock
    private SkillDefinitionRepository definitionRepository;
    @Mock
    private SkillVersionRepository versionRepository;
    @Mock
    private SkillCatalogChangePublisher catalogChangePublisher;

    private SkillCatalogRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillCatalogRegistry(definitionRepository, versionRepository, catalogChangePublisher);
    }

    @Test
    void refresh_publishesCatalogChanged() {
        when(definitionRepository.findByEnabledTrueOrderByIdAsc()).thenReturn(List.of());
        registry.refresh();
        verify(catalogChangePublisher).publish("default");
    }
}
