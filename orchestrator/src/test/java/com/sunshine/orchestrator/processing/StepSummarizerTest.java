package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.WorkflowProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepSummarizerTest {

    @BeforeEach
    void bindTimelineLabels() {
        TimelineLabelTestSupport.bindDefaults();
    }

    @AfterEach
    void unbindTimelineLabels() {
        TimelineLabelTestSupport.unbind();
    }

    @Test
    void intentAfter_knowledge_mentionsQuery() {
        WorkflowProperties workflowProps = new WorkflowProperties();
        WorkflowProperties.CatalogEntry entry = new WorkflowProperties.CatalogEntry();
        entry.setId("knowledge-qa");
        entry.setDisplayName("知识库问答");
        workflowProps.setCatalog(List.of(entry));
        workflowProps.setDefinitions(new LinkedHashMap<>());
        WorkflowNodeLabelService workflowLabels = new WorkflowNodeLabelService(
                workflowProps, Mockito.mock(ToolCatalogService.class), new AgentPromptProperties());
        WorkflowNodeLabels.bind(workflowLabels);
        IntentLabels.bind(new IntentLabelService(
                new AgentPromptProperties(), workflowProps, workflowLabels));
        String after = StepSummarizer.after("intent", "公司考勤制度是什么？", "知识库问答");
        assertThat(after).contains("公司考勤制度");
        assertThat(after).contains("知识库问答");
    }

    @Test
    void ragAfter_zeroHits_mentionsQuery() {
        String after = StepSummarizer.after("rag", "我怎么请假", "命中 0 条");
        assertThat(after).contains("请假");
        assertThat(after).contains("未找到");
    }

    @Test
    void thinkAfter_withToolDisplayName_usesToolNotQuery() {
        String after = StepSummarizer.after("think-2", "请自主依次调用三个工具", null, "统计财务消息");
        assertThat(after).isEqualTo("已完成「统计财务消息」的工具结果综合分析");
        assertThat(after).doesNotContain("请自主");
    }

    @Test
    void ragAfter_withHits_mentionsCountAndQuery() {
        String after = StepSummarizer.after("rag", "考勤制度", "命中 3 条");
        assertThat(after).contains("3 条");
        assertThat(after).contains("考勤制度");
    }

    @Test
    void ragAfter_withMetadata_usesDocTitlesOnly() {
        StepMetadata metadata = new StepMetadata(3, List.of("公司请假流程规范"), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        String after = StepSummarizer.afterRag("项目预算审批流程", "命中 0 条", metadata);
        assertThat(after).isEqualTo("找到 3 条参考片段，来源：公司请假流程规范");
    }

    @Test
    void ragAfter_timestampStepId_doesNotEmbedFragments() {
        String raw = """
                知识库检索结果（共 3 条）：
                来源文档：公司请假流程规范

                【公司请假流程规范 | 片段 1】
                表格内容不应出现
                """;
        String after = StepSummarizer.after("rag@1718750000123", "项目预算审批流程", raw);
        assertThat(after).contains("3 条");
        assertThat(after).doesNotContain("【");
        assertThat(after).doesNotContain("表格");
    }

    @Test
    void generateAfter_mentionsQuery() {
        String after = StepSummarizer.after("generate", "你好", null);
        assertThat(after).contains("你好");
        assertThat(after).contains("已完成");
    }

    @Test
    void before_active_includeUserQuery() {
        assertThat(StepSummarizer.before("intent", "测试问题"))
                .isEqualTo("阅读「测试问题」");
        assertThat(StepSummarizer.active("rag", "测试问题"))
                .contains("测试问题");
    }

    @Test
    void clipQuery_skillMention_keepsFullTokenAndMoreEnglish() {
        String clipped = StepSummarizer.clipQuery("@finance-analysis 先查制度再分析");
        assertThat(clipped).isEqualTo("「@finance-analysis 先查制度再分析」");
        assertThat(clipped).doesNotContain("…");
    }

    @Test
    void clipQuery_longEnglish_usesDisplayBudgetNotCharCount() {
        String query = "@finance-analysis please analyze reimbursement compliance";
        String clipped = StepSummarizer.clipQuery(query);
        assertThat(clipped).startsWith("「@finance-analysis");
        assertThat(clipped).contains("…");
        assertThat(StepSummarizer.clipByDisplayBudget(query, 36).length()).isGreaterThan(18);
    }
}
