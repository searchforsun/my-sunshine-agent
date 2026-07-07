package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolNodeLabelServiceTest {

    @Mock
    private ToolCatalogService catalogService;

    @BeforeEach
    void setUp() {
        ToolNodeLabels.bind(new ToolNodeLabelService(new AgentPromptProperties(), catalogService));
    }

    @AfterEach
    void tearDown() {
        ToolNodeLabels.bind(null);
    }

    @Test
    void toolTemplates_useCatalogDisplayName() {
        when(catalogService.displayName("list_oa_tasks")).thenReturn("查询 OA 待办");
        assertThat(ToolNodeLabels.toolLabel("tool-list_oa_tasks")).isEqualTo("调用工具 查询 OA 待办");
        assertThat(ToolNodeLabels.toolBefore("tool-list_oa_tasks")).isEqualTo("准备查询 OA 待办");
        assertThat(ToolNodeLabels.toolActive("tool-list_oa_tasks")).isEqualTo("正在查询 OA 待办");
        assertThat(ToolNodeLabels.toolAfter("tool-list_oa_tasks", null)).isEqualTo("查询 OA 待办完成");
    }

    @Test
    void nodeBefore_withQuery_usesBeforeWithQueryTemplate() {
        String q = StepSummarizer.clipQuery("预算审批");
        assertThat(ToolNodeLabels.nodeBefore("node-n1", q, "检索制度"))
                .isEqualTo("准备处理「预算审批」的「检索制度」环节");
    }
}
