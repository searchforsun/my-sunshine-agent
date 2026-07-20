package com.sunshine.prompt.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.prompt.dto.PromptCreateRequest;
import com.sunshine.prompt.dto.PromptDetailResponse;
import com.sunshine.prompt.dto.PromptPublishRequest;
import com.sunshine.prompt.dto.PromptRollbackRequest;
import com.sunshine.prompt.dto.PromptVersionRequest;
import com.sunshine.prompt.entity.PromptCatalogMetaEntity;
import com.sunshine.prompt.entity.PromptDefinitionEntity;
import com.sunshine.prompt.entity.PromptVersionEntity;
import com.sunshine.prompt.exception.PromptErrorCode;
import com.sunshine.prompt.repo.PromptCatalogMetaRepository;
import com.sunshine.prompt.repo.PromptDefinitionRepository;
import com.sunshine.prompt.repo.PromptVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromptAdminServiceTest {
    @Mock
    private PromptDefinitionRepository definitionRepository;
    @Mock
    private PromptVersionRepository versionRepository;
    @Mock
    private PromptCatalogMetaRepository catalogMetaRepository;
    @InjectMocks
    private PromptAdminService promptAdminService;

    private PromptCatalogMetaEntity catalogMeta;
    private Instant baseTime;

    @BeforeEach
    void setUp() {
        baseTime = Instant.parse("2026-07-20T02:00:00Z");
        catalogMeta = new PromptCatalogMetaEntity();
        catalogMeta.setId((byte) 1);
        catalogMeta.setCatalogVersion(1L);
        catalogMeta.setUpdatedAt(baseTime);
        when(catalogMetaRepository.findById((byte) 1)).thenReturn(Optional.of(catalogMeta));
        lenient().when(catalogMetaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(definitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createDraftPublishAndRollback_bumpsCatalogVersion() {
        when(definitionRepository.existsById("rule-a")).thenReturn(false);
        when(definitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepository.findTopByPromptIdOrderByVersionDesc("rule-a")).thenReturn(Optional.empty());
        when(versionRepository.findTopByPromptIdAndStatusOrderByVersionDesc("rule-a", "draft"))
                .thenAnswer(inv -> versionRepository.findByPromptIdAndVersion("rule-a", 1));
        when(versionRepository.findByPromptIdAndVersion("rule-a", 1)).thenAnswer(inv -> {
            PromptVersionEntity v = draftVersion(1, "{\"v\":1}");
            return Optional.of(v);
        });

        PromptDetailResponse created = promptAdminService.create(new PromptCreateRequest(
                "rule-a", "routing-rule", "规则 A", null, 10, true,
                "draft", null, "{\"v\":1}", "init", null));
        assertThat(created.activeVersion()).isEqualTo(1);
        assertThat(catalogMeta.getCatalogVersion()).isEqualTo(1L);
        verify(catalogMetaRepository, never()).save(any());

        PromptDefinitionEntity def = savedDefinition("rule-a");
        when(definitionRepository.findById("rule-a")).thenReturn(Optional.of(def));
        when(versionRepository.findByPromptIdAndVersion("rule-a", 1)).thenReturn(Optional.of(draftVersion(1, "{\"v\":1}")));

        PromptDetailResponse published = promptAdminService.publish("rule-a", new PromptPublishRequest(null, "ops", null));
        assertThat(published.activeVersion()).isEqualTo(1);
        assertThat(catalogMeta.getCatalogVersion()).isEqualTo(2L);

        when(versionRepository.findTopByPromptIdOrderByVersionDesc("rule-a")).thenReturn(Optional.of(publishedVersion(1)));
        when(versionRepository.findByPromptIdAndVersion("rule-a", 2)).thenReturn(Optional.of(draftVersion(2, "{\"v\":2}")));
        promptAdminService.addVersion("rule-a", new PromptVersionRequest(
                "draft", null, "{\"v\":2}", "v2 draft", null, null));
        assertThat(catalogMeta.getCatalogVersion()).isEqualTo(2L);

        when(versionRepository.findTopByPromptIdAndStatusOrderByVersionDesc("rule-a", "draft"))
                .thenReturn(Optional.of(draftVersion(2, "{\"v\":2}")));
        when(versionRepository.findByPromptIdAndVersion("rule-a", 2)).thenAnswer(inv -> {
            PromptVersionEntity v = draftVersion(2, "{\"v\":2}");
            v.setStatus("published");
            return Optional.of(v);
        });
        PromptDetailResponse publishedV2 = promptAdminService.publish("rule-a", new PromptPublishRequest(null, null, null));
        assertThat(publishedV2.activeVersion()).isEqualTo(2);
        assertThat(catalogMeta.getCatalogVersion()).isEqualTo(3L);

        when(versionRepository.findByPromptIdAndVersion("rule-a", 1)).thenReturn(Optional.of(publishedVersion(1)));
        PromptDetailResponse rolledBack = promptAdminService.rollback("rule-a", new PromptRollbackRequest(1, null));
        assertThat(rolledBack.activeVersion()).isEqualTo(1);
        assertThat(catalogMeta.getCatalogVersion()).isEqualTo(4L);
        verify(catalogMetaRepository, times(3)).save(catalogMeta);
    }

    @Test
    void rollbackRejectsDraftVersion() {
        PromptDefinitionEntity def = savedDefinition("rule-a");
        when(definitionRepository.findById("rule-a")).thenReturn(Optional.of(def));
        when(versionRepository.findByPromptIdAndVersion("rule-a", 2)).thenReturn(Optional.of(draftVersion(2, "{}")));

        assertThatThrownBy(() -> promptAdminService.rollback("rule-a", new PromptRollbackRequest(2, null)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(PromptErrorCode.ROLLBACK_REQUIRES_PUBLISHED);
        verify(catalogMetaRepository, never()).save(any());
    }

    @Test
    void optimisticLockReturnsConflict() {
        PromptDefinitionEntity def = savedDefinition("rule-a");
        Instant stale = baseTime.minusSeconds(60);
        when(definitionRepository.findById("rule-a")).thenReturn(Optional.of(def));

        assertThatThrownBy(() -> promptAdminService.update("rule-a",
                new com.sunshine.prompt.dto.PromptUpdateRequest("新名", null, null, stale)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(CommonErrorCode.CONFLICT);
    }

    @Test
    void publishWithExplicitVersion_bumpsCatalog() {
        PromptDefinitionEntity def = savedDefinition("rule-a");
        when(definitionRepository.findById("rule-a")).thenReturn(Optional.of(def));
        PromptVersionEntity draft = draftVersion(3, "{\"v\":3}");
        when(versionRepository.findByPromptIdAndVersion("rule-a", 3)).thenReturn(Optional.of(draft));

        PromptDetailResponse response = promptAdminService.publish("rule-a", new PromptPublishRequest(3, "ops", null));

        assertThat(response.activeVersion()).isEqualTo(3);
        assertThat(catalogMeta.getCatalogVersion()).isEqualTo(2L);
        ArgumentCaptor<PromptVersionEntity> versionCaptor = ArgumentCaptor.forClass(PromptVersionEntity.class);
        verify(versionRepository).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getStatus()).isEqualTo("published");
    }

    private PromptDefinitionEntity savedDefinition(String id) {
        PromptDefinitionEntity def = new PromptDefinitionEntity();
        def.setId(id);
        def.setKind("routing-rule");
        def.setDisplayName("规则 A");
        def.setEnabled(true);
        def.setPriority(10);
        def.setActiveVersion(1);
        def.setCatalogVersion(1L);
        def.setCreatedAt(baseTime);
        def.setUpdatedAt(baseTime);
        return def;
    }

    private PromptVersionEntity draftVersion(int version, String json) {
        PromptVersionEntity entity = new PromptVersionEntity();
        entity.setPromptId("rule-a");
        entity.setVersion(version);
        entity.setStatus("draft");
        entity.setContentJson(json);
        entity.setCreatedAt(baseTime);
        return entity;
    }

    private PromptVersionEntity publishedVersion(int version) {
        PromptVersionEntity entity = draftVersion(version, "{\"v\":" + version + "}");
        entity.setStatus("published");
        return entity;
    }
}
