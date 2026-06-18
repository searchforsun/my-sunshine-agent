package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.WorkflowProperties;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowNodeLabelsTest {

    @Mock
    private ToolCatalogService toolCatalogService;

    private WorkflowNodeLabelService labelService;

    @BeforeEach
    void setUp() {
        WorkflowProperties props = new WorkflowProperties();
        WorkflowProperties.CatalogEntry entry = new WorkflowProperties.CatalogEntry();
        entry.setId("finance-list");
        entry.setDisplayName("财务待办查询");
        props.setCatalog(List.of(entry));

        WorkflowProperties.WorkflowDefinitionProps def = new WorkflowProperties.WorkflowDefinitionProps();
        WorkflowProperties.NodeProps toolNode = new WorkflowProperties.NodeProps();
        toolNode.setId("finance-list");
        toolNode.setType("tool");
        toolNode.setDisplayName("查询待审批财务消息");
        toolNode.setParams(Map.of("tool", "list_finance_messages"));
        WorkflowProperties.NodeProps llmNode = new WorkflowProperties.NodeProps();
        llmNode.setId("llm");
        llmNode.setType("llm");
        WorkflowProperties.NodeProps startNode = new WorkflowProperties.NodeProps();
        startNode.setId("start");
        startNode.setType("start");
        WorkflowProperties.NodeProps answerNode = new WorkflowProperties.NodeProps();
        answerNode.setId("answer");
        answerNode.setType("answer");
        def.setNodes(List.of(startNode, toolNode, llmNode, answerNode));
        props.setDefinitions(new LinkedHashMap<>(Map.of("finance-list", def)));

        labelService = new WorkflowNodeLabelService(props, toolCatalogService);
        WorkflowNodeLabels.bind(labelService);
    }

    @AfterEach
    void tearDown() {
        WorkflowNodeLabels.bind(null);
    }

    @Test
    void planChainSkipsStartAndAnswer() {
        WorkflowDefinition def = WorkflowDefinition.from("finance-list", List.of(
                new NodeSpec("start", "start", Map.of()),
                new NodeSpec("finance-list", "tool", Map.of("tool", "list_finance_messages")),
                new NodeSpec("llm", "llm", Map.of()),
                new NodeSpec("answer", "answer", Map.of())
        ), List.of("start", "finance-list", "llm", "answer"));

        assertThat(WorkflowNodeLabels.planChain(def))
                .isEqualTo("查询待审批财务消息 → 生成回答");
    }

    @Test
    void displayNameByStepId_resolvesLlmWithoutExposingInternalType() {
        assertThat(WorkflowNodeLabels.displayNameByStepId("node-llm")).isEqualTo("生成回答");
    }

    @Test
    void workflowDisplayNameUsesCatalogDisplayName() {
        assertThat(WorkflowNodeLabels.workflowDisplayName("finance-list"))
                .isEqualTo("财务待办查询");
    }
}
