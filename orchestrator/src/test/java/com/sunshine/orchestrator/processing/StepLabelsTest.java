package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StepLabelsTest {

    @Mock
    private ToolCatalogService catalogService;

    @BeforeEach
    void bindCatalog() {
        TimelineLabelTestSupport.bindDefaults();
        ToolNodeLabels.bind(new ToolNodeLabelService(TimelinePromptCatalog.withDefaults(), catalogService));
        StepLabels.bind(catalogService);
    }

    @AfterEach
    void unbindCatalog() {
        StepLabels.bind(null);
        TimelineLabelTestSupport.unbind();
    }

    @Test
    void toolDisplayName_readsFromCatalog() {
        when(catalogService.displayName("sdk__sunshine-finance__list_my_expenses")).thenReturn("查询待审批财务消息");
        assertThat(StepLabels.toolDisplayName("tool-sdk__sunshine-finance__list_my_expenses"))
                .isEqualTo("查询待审批财务消息");
    }

    @Test
    void labelFor_toolStep_usesCatalogDisplayName() {
        when(catalogService.displayName("sdk__sunshine-oa__list_oa_tasks")).thenReturn("查询 OA 待办");
        assertThat(StepLabels.labelFor("tool-sdk__sunshine-oa__list_oa_tasks"))
                .isEqualTo("调用工具 查询 OA 待办");
    }

    @Test
    void labelFor_toolStepWithTimestampId_usesDisplayNameOnly() {
        when(catalogService.displayName("sdk__sunshine-finance__summarize_my_expenses")).thenReturn("统计财务消息");
        assertThat(StepLabels.labelFor("tool-sdk__sunshine-finance__summarize_my_expenses@1718750000123"))
                .isEqualTo("调用工具 统计财务消息");
    }
}
