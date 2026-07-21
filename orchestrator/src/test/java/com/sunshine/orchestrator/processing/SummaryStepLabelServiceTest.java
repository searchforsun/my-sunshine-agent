package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class SummaryStepLabelServiceTest {

    private ToolCatalogService toolCatalogService;

    @BeforeEach
    void setUp() {
        TimelinePromptCatalog timelineCatalog = TimelinePromptCatalog.withDefaults();
        toolCatalogService = Mockito.mock(ToolCatalogService.class);
        TimelineLabelTestSupport.stubDefaultSummarize(toolCatalogService);
        SummaryStepLabels.bind(new SummaryStepLabelService(timelineCatalog, toolCatalogService));
    }

    @AfterEach
    void tearDown() {
        SummaryStepLabels.bind(null);
    }

    @Test
    void agentTemplates_useQueryPlaceholder() {
        String q = StepSummarizer.clipQuery("报销制度");
        assertThat(SummaryStepLabels.agentBefore(q)).isEqualTo("理解「报销制度」，规划作答思路");
        assertThat(SummaryStepLabels.agentActive(q)).isEqualTo("结合上下文分析「报销制度」");
    }

    @Test
    void ragAfter_withMetadata_usesDocTitlesOnly() {
        StepMetadata metadata = new StepMetadata(
                3, List.of("公司请假流程规范"), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
        String after = SummaryStepLabels.ragAfter("「项目预算审批流程」", "命中 0 条", metadata);
        assertThat(after).isEqualTo("找到 3 条参考片段，来源：公司请假流程规范");
    }
}
