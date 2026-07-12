package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.catalog.WorkflowCatalogRegistry;
import com.sunshine.orchestrator.client.WorkflowManagerClient;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.WorkflowCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class IntentLabelServiceTest {

    @Mock
    private ToolCatalogService toolCatalogService;
    @Mock
    private WorkflowCatalogRegistry workflowCatalogRegistry;
    @Mock
    private WorkflowManagerClient workflowManagerClient;

    private IntentLabelService intentLabelService;
    private TimelineStepLabelService timelineStepLabelService;
    private ThinkStepLabelService thinkStepLabelService;

    @BeforeEach
    void setUp() {
        AgentPromptProperties agentProps = new AgentPromptProperties();
        stubCatalog();
        WorkflowCatalog workflowCatalog = new WorkflowCatalog(workflowCatalogRegistry, workflowManagerClient);
        WorkflowNodeLabelService workflowLabels = new WorkflowNodeLabelService(
                workflowCatalog, toolCatalogService);
        WorkflowNodeLabels.bind(workflowLabels);
        timelineStepLabelService = new TimelineStepLabelService(agentProps);
        thinkStepLabelService = new ThinkStepLabelService(agentProps);
        intentLabelService = new IntentLabelService(
                agentProps, workflowCatalog, workflowCatalogRegistry, workflowLabels);
        IntentLabels.bind(intentLabelService);
        TimelineLabels.bind(timelineStepLabelService);
        TimelineStepLabels.bind(timelineStepLabelService);
        ThinkStepLabels.bind(thinkStepLabelService);
    }

    private void stubCatalog() {
        WorkflowManagerClient.WorkflowCatalogEntryDto finance =
                new WorkflowManagerClient.WorkflowCatalogEntryDto(
                        "finance-list", "workflow", "财务待办查询", "财务待办", List.of(), List.of(), null);
        WorkflowManagerClient.WorkflowCatalogEntryDto knowledge =
                new WorkflowManagerClient.WorkflowCatalogEntryDto(
                        "knowledge-qa", "workflow", "知识库问答", "查制度", List.of(), List.of(), null);
        lenient().when(workflowCatalogRegistry.entries()).thenReturn(List.of(finance, knowledge));
        lenient().when(workflowCatalogRegistry.find("finance-list")).thenReturn(finance);
        lenient().when(workflowCatalogRegistry.find("knowledge-qa")).thenReturn(knowledge);
    }

    @AfterEach
    void tearDown() {
        IntentLabels.bind(null);
        TimelineLabels.bind(null);
        TimelineStepLabels.bind(null);
        ThinkStepLabels.bind(null);
        WorkflowNodeLabels.bind(null);
    }

    @Test
    void intentDetail_workflowUsesDisplayName() {
        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.WORKFLOW, "finance-list", Map.of(), "test");
        assertThat(intentLabelService.intentDetail(plan)).isEqualTo("财务待办查询");
    }

    @Test
    void intentAfterForPlan_workflowIncludesDisplayName() {
        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "test");
        String after = intentLabelService.intentAfterForPlan("公司考勤制度是什么？", plan);
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
}
