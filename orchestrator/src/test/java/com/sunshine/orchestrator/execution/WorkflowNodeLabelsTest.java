package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.WorkflowManagerClient;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowNodeLabelsTest {

    @Mock
    private WorkflowCatalog workflowCatalog;
    @Mock
    private ToolCatalogService toolCatalogService;

    private WorkflowNodeLabelService labelService;

    @BeforeEach
    void setUp() {
        lenient().when(workflowCatalog.findEntry("finance-list")).thenReturn(
                new WorkflowManagerClient.WorkflowCatalogEntryDto(
                        "finance-list", "workflow", "财务待办查询", "财务待办", List.of(), List.of(), null));
        labelService = new WorkflowNodeLabelService(workflowCatalog, toolCatalogService);
        WorkflowNodeLabels.bind(labelService);
    }

    @AfterEach
    void tearDown() {
        WorkflowNodeLabels.bind(null);
    }

    @Test
    void requiresBoundService() {
        WorkflowNodeLabels.bind(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> WorkflowNodeLabels.displayName("rag", "rag"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WorkflowNodeLabelService");
    }

    @Test
    void planChainSkipsStartAndAnswer() {
        WorkflowDefinition def = WorkflowDefinition.from("finance-list", List.of(
                new NodeSpec("start", "start", Map.of()),
                new NodeSpec("finance-list", "tool", Map.of("tool", "sdk__sunshine-finance__list_finance_messages"),
                        "查询待审批财务消息"),
                new NodeSpec("answer", "answer", Map.of(), "生成回答")
        ), List.of("start", "finance-list", "answer"));

        assertThat(WorkflowNodeLabels.planChain(def))
                .isEqualTo("查询待审批财务消息");
    }

    @Test
    void displayNameByStepId_resolvesLlmWithoutExposingInternalType() {
        assertThat(WorkflowNodeLabels.displayNameByStepId("node-llm")).isEqualTo("综合分析");
    }

    @Test
    void workflowDisplayNameUsesCatalogDisplayName() {
        assertThat(WorkflowNodeLabels.workflowDisplayName("finance-list"))
                .isEqualTo("财务待办查询");
    }
}
