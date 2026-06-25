package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillLoadLabelServiceTest {

    @Mock
    private SkillCatalogService skillCatalogService;

    @AfterEach
    void tearDown() {
        SkillLoadLabels.bind(null);
    }

    @Test
    void afterLine_includesSkillIdAndDisplayName() {
        when(skillCatalogService.findIndex("skill-demo")).thenReturn(Optional.of(
                new SkillCatalogIndexEntry("skill-demo", "测试技能", "desc", 1, true)));
        SkillLoadLabelService service = new SkillLoadLabelService(skillCatalogService, new AgentPromptProperties());
        service.init();
        assertThat(SkillLoadLabels.after("skill-demo")).isEqualTo("@skill-demo 测试技能");
    }

    @Test
    void afterLine_withoutDisplayName_usesIdOnly() {
        when(skillCatalogService.findIndex("finance-analysis")).thenReturn(Optional.empty());
        SkillLoadLabelService service = new SkillLoadLabelService(skillCatalogService, new AgentPromptProperties());
        service.init();
        assertThat(SkillLoadLabels.after("finance-analysis")).isEqualTo("@finance-analysis");
    }
}
