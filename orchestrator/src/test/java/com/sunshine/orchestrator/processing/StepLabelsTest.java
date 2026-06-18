package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
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
        StepLabels.bind(catalogService);
    }

    @AfterEach
    void unbindCatalog() {
        StepLabels.bind(null);
    }

    @Test
    void toolDisplayName_readsFromCatalog() {
        when(catalogService.displayName("list_finance_messages")).thenReturn("查询待审批财务消息");
        assertThat(StepLabels.toolDisplayName("tool-list_finance_messages"))
                .isEqualTo("查询待审批财务消息");
    }

    @Test
    void labelFor_toolStep_usesCatalogDisplayName() {
        when(catalogService.displayName("list_oa_tasks")).thenReturn("查询 OA 待办");
        assertThat(StepLabels.labelFor("tool-list_oa_tasks"))
                .isEqualTo("调用工具 查询 OA 待办");
    }
}
