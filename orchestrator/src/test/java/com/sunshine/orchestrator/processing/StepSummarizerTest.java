package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.WorkflowProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepSummarizerTest {

    @Test
    void intentAfter_knowledge_mentionsQuery() {
        AgentPromptProperties agentProps = new AgentPromptProperties();
        WorkflowProperties workflowProps = new WorkflowProperties();
        WorkflowProperties.CatalogEntry entry = new WorkflowProperties.CatalogEntry();
        entry.setId("knowledge-qa");
        entry.setDisplayName("知识库问答");
        workflowProps.setCatalog(List.of(entry));
        workflowProps.setDefinitions(new java.util.LinkedHashMap<>());
        IntentLabels.bind(new IntentLabelService(
                agentProps, workflowProps, new WorkflowNodeLabelService(
                        workflowProps, Mockito.mock(com.sunshine.orchestrator.catalog.ToolCatalogService.class))));
        try {
            String after = StepSummarizer.after("intent", "公司考勤制度是什么？", "知识库问答");
            assertThat(after).contains("公司考勤制度");
            assertThat(after).contains("知识库问答");
        } finally {
            IntentLabels.bind(null);
        }
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
        StepMetadata metadata = new StepMetadata(3, List.of("公司请假流程规范"));
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
}
