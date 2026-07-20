package com.sunshine.prompt.service;

import com.sunshine.prompt.dto.PromptCatalogResponse;
import com.sunshine.prompt.entity.PromptCatalogMetaEntity;
import com.sunshine.prompt.entity.PromptDefinitionEntity;
import com.sunshine.prompt.entity.PromptVersionEntity;
import com.sunshine.prompt.repo.PromptCatalogMetaRepository;
import com.sunshine.prompt.repo.PromptDefinitionRepository;
import com.sunshine.prompt.repo.PromptVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptCatalogServiceTest {
    @Mock
    private PromptDefinitionRepository definitionRepository;
    @Mock
    private PromptVersionRepository versionRepository;
    @Mock
    private PromptCatalogMetaRepository catalogMetaRepository;
    @InjectMocks
    private PromptCatalogService promptCatalogService;

    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-07-20T03:00:00Z");
        PromptCatalogMetaEntity meta = new PromptCatalogMetaEntity();
        meta.setId((byte) 1);
        meta.setCatalogVersion(12L);
        meta.setUpdatedAt(now);
        when(catalogMetaRepository.findById((byte) 1)).thenReturn(Optional.of(meta));
    }

    @Test
    void catalog_includesOnlyEnabledPublishedActive() {
        PromptDefinitionEntity react = def("mode-overlay.react", "mode-overlay", "ReAct", true, 0, 1);
        PromptDefinitionEntity draftActive = def("system-prompt", "system", "系统提示", true, 0, 2);
        PromptDefinitionEntity disabled = def("planner.prompt", "planner", "Planner", false, 0, 1);
        PromptDefinitionEntity missingVer = def("intent.classifier", "intent", "意图", true, 0, 9);
        when(definitionRepository.findByEnabled(true)).thenReturn(List.of(react, draftActive, missingVer));

        when(versionRepository.findByPromptIdAndVersion("mode-overlay.react", 1))
                .thenReturn(Optional.of(published("mode-overlay.react", 1, "react body", null)));
        when(versionRepository.findByPromptIdAndVersion("system-prompt", 2))
                .thenReturn(Optional.of(draft("system-prompt", 2, "sys", null)));
        when(versionRepository.findByPromptIdAndVersion("intent.classifier", 9))
                .thenReturn(Optional.empty());

        PromptCatalogResponse resp = promptCatalogService.catalog();
        assertThat(resp.catalogVersion()).isEqualTo(12L);
        assertThat(resp.entries()).hasSize(1);
        assertThat(resp.entries().get(0).id()).isEqualTo("mode-overlay.react");
        assertThat(resp.entries().get(0).contentText()).isEqualTo("react body");
        assertThat(disabled.isEnabled()).isFalse();
    }

    @Test
    void catalog_ordersByPriorityDescThenId() {
        when(definitionRepository.findByEnabled(true)).thenReturn(List.of(
                def("routing-rule.b", "routing-rule", "B", true, 10, 1),
                def("routing-rule.a", "routing-rule", "A", true, 100, 1),
                def("routing-rule.c", "routing-rule", "C", true, 10, 1)));
        when(versionRepository.findByPromptIdAndVersion("routing-rule.a", 1))
                .thenReturn(Optional.of(published("routing-rule.a", 1, null, "{\"p\":1}")));
        when(versionRepository.findByPromptIdAndVersion("routing-rule.b", 1))
                .thenReturn(Optional.of(published("routing-rule.b", 1, null, "{\"p\":1}")));
        when(versionRepository.findByPromptIdAndVersion("routing-rule.c", 1))
                .thenReturn(Optional.of(published("routing-rule.c", 1, null, "{\"p\":1}")));

        PromptCatalogResponse resp = promptCatalogService.catalog();
        assertThat(resp.entries()).extracting(e -> e.id())
                .containsExactly("routing-rule.a", "routing-rule.b", "routing-rule.c");
    }

    private PromptDefinitionEntity def(String id, String kind, String name, boolean enabled,
                                       int priority, int activeVersion) {
        PromptDefinitionEntity d = new PromptDefinitionEntity();
        d.setId(id);
        d.setKind(kind);
        d.setDisplayName(name);
        d.setEnabled(enabled);
        d.setPriority(priority);
        d.setActiveVersion(activeVersion);
        d.setCatalogVersion(1L);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        return d;
    }

    private PromptVersionEntity published(String promptId, int version, String text, String json) {
        PromptVersionEntity v = new PromptVersionEntity();
        v.setPromptId(promptId);
        v.setVersion(version);
        v.setStatus("published");
        v.setContentText(text);
        v.setContentJson(json);
        v.setCreatedAt(now);
        return v;
    }

    private PromptVersionEntity draft(String promptId, int version, String text, String json) {
        PromptVersionEntity v = published(promptId, version, text, json);
        v.setStatus("draft");
        return v;
    }
}
