package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.catalog.WorkflowCatalogRegistry;
import com.sunshine.orchestrator.client.WorkflowManagerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowCatalogTest {

    @Mock
    private WorkflowCatalogRegistry catalogRegistry;
    @Mock
    private WorkflowManagerClient workflowManagerClient;

    private WorkflowCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new WorkflowCatalog(catalogRegistry, workflowManagerClient);
    }

    @Test
    void rendersCatalogForPrompt() {
        when(catalogRegistry.entries()).thenReturn(List.of(
                new WorkflowManagerClient.WorkflowCatalogEntryDto(
                        "knowledge-qa", "workflow", "知识库问答", "查制度",
                        List.of("年假"), List.of("start", "rag", "answer"), null)));

        String rendered = catalog.renderForPrompt();

        assertThat(rendered).contains("knowledge-qa").contains("查制度");
    }

    @Test
    void validateWorkflowIdExists() {
        lenient().when(catalogRegistry.find("knowledge-qa")).thenReturn(
                new WorkflowManagerClient.WorkflowCatalogEntryDto(
                        "knowledge-qa", "workflow", "知识库问答", "查制度", List.of(), List.of(), null));
        lenient().when(catalogRegistry.find("missing")).thenReturn(null);

        assertThat(catalog.isKnownWorkflow("knowledge-qa")).isTrue();
        assertThat(catalog.isKnownWorkflow("missing")).isFalse();
    }

    @Test
    void sanitizeUnknownWorkflowFallsBackToReact() {
        when(catalogRegistry.find("unknown")).thenReturn(null);

        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.WORKFLOW, "unknown", java.util.Map.of(), "test");

        ExecutionPlan sanitized = catalog.sanitize(plan);
        assertThat(sanitized.mode()).isEqualTo(ExecutionMode.FAST);
    }

    @Test
    void renderIntoClassifierReplacesPlaceholder() {
        when(catalogRegistry.entries()).thenReturn(List.of(
                new WorkflowManagerClient.WorkflowCatalogEntryDto(
                        "knowledge-qa", "workflow", "知识库问答", "查制度", List.of(), List.of(), null)));

        String prompt = catalog.renderIntoClassifier("目录：\n{{workflow-catalog}}\n结束");

        assertThat(prompt).contains("knowledge-qa").doesNotContain("{{workflow-catalog}}");
    }
}
