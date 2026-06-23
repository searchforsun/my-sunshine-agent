package com.sunshine.skill.service;

import com.sunshine.skill.entity.SkillDefinitionEntity;
import com.sunshine.skill.entity.SkillVersionEntity;
import com.sunshine.skill.repo.SkillDefinitionRepository;
import com.sunshine.skill.repo.SkillVersionRepository;
import com.sunshine.skill.storage.SkillStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillAdminServiceForkTest {

    @Mock
    private SkillDefinitionRepository definitionRepository;
    @Mock
    private SkillVersionRepository versionRepository;
    @Mock
    private SkillStorageService skillStorageService;
    @Mock
    private SkillCatalogRegistry catalogRegistry;

    @InjectMocks
    private SkillAdminService skillAdminService;

    @Test
    void forkVersion_createsNewDraftFromPublished() {
        SkillDefinitionEntity def = new SkillDefinitionEntity();
        def.setId("finance-analysis");
        def.setActiveVersion(1);
        SkillVersionEntity source = publishedVersion(1, "/store/v1/SKILL.md");
        SkillVersionEntity latest = publishedVersion(1, "/store/v1/SKILL.md");
        when(definitionRepository.findById("finance-analysis")).thenReturn(Optional.of(def));
        when(versionRepository.findBySkillIdAndVersion("finance-analysis", 1))
                .thenReturn(Optional.of(source));
        when(versionRepository.findFirstBySkillIdAndStatusOrderByVersionDesc("finance-analysis", "draft"))
                .thenReturn(Optional.empty());
        when(versionRepository.findTopBySkillIdOrderByVersionDesc("finance-analysis"))
                .thenReturn(Optional.of(latest));
        when(skillStorageService.copyPackage("finance-analysis", 1, "/store/v1/SKILL.md", 2))
                .thenReturn("/store/v2/SKILL.md");

        skillAdminService.forkVersion("finance-analysis", 1, "user-1");

        ArgumentCaptor<SkillVersionEntity> captor = ArgumentCaptor.forClass(SkillVersionEntity.class);
        verify(versionRepository).save(captor.capture());
        SkillVersionEntity saved = captor.getValue();
        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo("draft");
        assertThat(saved.getSystemOverlay()).isEqualTo("overlay-body");
        verify(catalogRegistry).refresh();
    }

    @Test
    void forkVersion_rejectsWhenContentDraftExists() {
        SkillDefinitionEntity def = new SkillDefinitionEntity();
        def.setId("finance-analysis");
        SkillVersionEntity source = publishedVersion(1, "/store/v1/SKILL.md");
        SkillVersionEntity existingDraft = new SkillVersionEntity();
        existingDraft.setSkillId("finance-analysis");
        existingDraft.setVersion(2);
        existingDraft.setStatus("draft");
        existingDraft.setStoragePath("/store/v2/SKILL.md");
        when(definitionRepository.findById("finance-analysis")).thenReturn(Optional.of(def));
        when(versionRepository.findBySkillIdAndVersion("finance-analysis", 1))
                .thenReturn(Optional.of(source));
        when(versionRepository.findFirstBySkillIdAndStatusOrderByVersionDesc("finance-analysis", "draft"))
                .thenReturn(Optional.of(existingDraft));

        assertThatThrownBy(() -> skillAdminService.forkVersion("finance-analysis", 1, "user-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("已有草稿版本");
    }

    @Test
    void forkVersion_rejectsEmptySource() {
        SkillDefinitionEntity def = new SkillDefinitionEntity();
        def.setId("finance-analysis");
        SkillVersionEntity source = new SkillVersionEntity();
        source.setSkillId("finance-analysis");
        source.setVersion(1);
        source.setStoragePath(null);
        when(definitionRepository.findById("finance-analysis")).thenReturn(Optional.of(def));
        when(versionRepository.findBySkillIdAndVersion("finance-analysis", 1))
                .thenReturn(Optional.of(source));

        assertThatThrownBy(() -> skillAdminService.forkVersion("finance-analysis", 1, "user-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("无 Skill 包");
    }

    private static SkillVersionEntity publishedVersion(int version, String storagePath) {
        SkillVersionEntity ver = new SkillVersionEntity();
        ver.setSkillId("finance-analysis");
        ver.setVersion(version);
        ver.setStatus("published");
        ver.setStoragePath(storagePath);
        ver.setSystemOverlay("overlay-body");
        ver.setToolsJson("[]");
        ver.setReferencesJson("[]");
        ver.setScriptsJson("[]");
        return ver;
    }
}
