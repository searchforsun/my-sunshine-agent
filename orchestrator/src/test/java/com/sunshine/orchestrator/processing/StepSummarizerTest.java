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
    void ragAfter_withHits_mentionsCountAndQuery() {
        String after = StepSummarizer.after("rag", "考勤制度", "命中 3 条");
        assertThat(after).contains("3 条");
        assertThat(after).contains("考勤制度");
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
