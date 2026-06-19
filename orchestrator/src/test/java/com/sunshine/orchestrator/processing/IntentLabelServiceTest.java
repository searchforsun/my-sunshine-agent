package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.WorkflowProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class IntentLabelServiceTest {

    @Mock
    private ToolCatalogService toolCatalogService;

    private IntentLabelService intentLabelService;

    @BeforeEach
    void setUp() {
        AgentPromptProperties agentProps = new AgentPromptProperties();
        WorkflowProperties workflowProps = buildWorkflowProps();
        WorkflowNodeLabelService workflowLabels = new WorkflowNodeLabelService(workflowProps, toolCatalogService);
        intentLabelService = new IntentLabelService(agentProps, workflowProps, workflowLabels);
        IntentLabels.bind(intentLabelService);
        TimelineLabels.bind(intentLabelService);
    }

    @AfterEach
    void tearDown() {
        IntentLabels.bind(null);
        TimelineLabels.bind(null);
    }

    @Test
    void intentDetail_workflowUsesCatalogDisplayName() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "finance-list", Map.of(), "test");
        assertThat(intentLabelService.intentDetail(plan)).isEqualTo("财务待办查询");
    }

    @Test
    void intentAfterSummary_workflowByDisplayName() {
        String q = StepSummarizer.clipQuery("我有哪些待审批报销");
        String after = intentLabelService.intentAfterSummary(q, "财务待办查询");
        assertThat(after).isEqualTo(q + "将按「财务待办查询」流程处理");
    }

    @Test
    void intentAfterSummary_simpleLlmMode() {
        String q = StepSummarizer.clipQuery("你好");
        assertThat(intentLabelService.intentAfterSummary(q, "简单对话"))
                .isEqualTo(q + "属于简单对话，将直接生成回复");
    }

    @Test
    void intentAfterSummary_reactMode() {
        String q = StepSummarizer.clipQuery("帮我查一下");
        assertThat(intentLabelService.intentAfterSummary(q, "自主智能体"))
                .isEqualTo(q + "将由自主智能体分析并作答");
    }

    @Test
    void intentAfterForPlan_workflow() {
        ExecutionPlan plan = new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "test");
        String after = intentLabelService.intentAfterForPlan("请假制度是什么", plan);
        assertThat(after).contains("知识库问答").contains("流程处理");
    }

    @Test
    void stepSummarizer_intentActive_noLegacyKnowledgeRoutingPhrase() {
        String after = StepSummarizer.active("intent", "我有哪些待审批报销");
        assertThat(after).contains("匹配");
        assertThat(after).doesNotContain("查阅知识库").doesNotContain("直接回答");
    }

    @Test
    void stepSummarizer_delegatesToConfig() {
        String after = StepSummarizer.after("intent", "公司考勤制度是什么？", "知识库问答");
        assertThat(after).contains("知识库问答").contains("流程处理");
    }

    private static WorkflowProperties buildWorkflowProps() {
        WorkflowProperties props = new WorkflowProperties();
        WorkflowProperties.CatalogEntry finance = new WorkflowProperties.CatalogEntry();
        finance.setId("finance-list");
        finance.setDisplayName("财务待办查询");
        WorkflowProperties.CatalogEntry knowledge = new WorkflowProperties.CatalogEntry();
        knowledge.setId("knowledge-qa");
        knowledge.setDisplayName("知识库问答");
        props.setCatalog(List.of(finance, knowledge));
        props.setDefinitions(new LinkedHashMap<>());
        return props;
    }
}
