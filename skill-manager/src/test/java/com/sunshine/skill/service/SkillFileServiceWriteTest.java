package com.sunshine.skill.service;

import com.sunshine.skill.dto.SkillFileContent;
import com.sunshine.skill.entity.SkillDefinitionEntity;
import com.sunshine.skill.entity.SkillVersionEntity;
import com.sunshine.skill.repo.SkillDefinitionRepository;
import com.sunshine.skill.repo.SkillVersionRepository;
import com.sunshine.skill.storage.SkillStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SkillFileServiceWriteTest {

    @Mock
    private SkillStorageService skillStorageService;
    @Mock
    private SkillDefinitionRepository definitionRepository;
    @Mock
    private SkillVersionRepository versionRepository;
    @Mock
    private SkillCatalogRegistry catalogRegistry;

    @InjectMocks
    private SkillFileService skillFileService;

    @Test
    void writeFile_rejectsPublishedVersion() {
        SkillDefinitionEntity def = new SkillDefinitionEntity();
        def.setId("finance-analysis");
        SkillVersionEntity ver = new SkillVersionEntity();
        ver.setSkillId("finance-analysis");
        ver.setVersion(1);
        ver.setStatus("published");
        ver.setStoragePath("/data/skills/finance-analysis/1/SKILL.md");
        whenDefinitionAndVersion(def, ver);

        assertThatThrownBy(() -> skillFileService.writeFile(
                "finance-analysis", 1, "SKILL.md", "---\nname: finance-analysis\ndescription: d\n---\nbody", "u1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("草稿");
        verifyNoInteractions(skillStorageService);
    }

    private void whenDefinitionAndVersion(SkillDefinitionEntity def, SkillVersionEntity ver) {
        org.mockito.Mockito.when(definitionRepository.findById(def.getId())).thenReturn(Optional.of(def));
        org.mockito.Mockito.when(versionRepository.findBySkillIdAndVersion(def.getId(), ver.getVersion()))
                .thenReturn(Optional.of(ver));
    }
}
