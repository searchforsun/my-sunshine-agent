package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.config.WorkflowProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCatalogTest {

    @Test
    void rendersCatalogForPrompt() {
        WorkflowProperties props = new WorkflowProperties();
        WorkflowProperties.CatalogEntry entry = new WorkflowProperties.CatalogEntry();
        entry.setId("knowledge-qa");
        entry.setMode("workflow");
        entry.setDesc("查制度");
        entry.setNodes(List.of("start", "rag", "llm", "answer"));
        props.setCatalog(List.of(entry));

        WorkflowCatalog catalog = new WorkflowCatalog(props);
        String rendered = catalog.renderForPrompt();

        assertThat(rendered).contains("knowledge-qa").contains("查制度");
    }

    @Test
    void validateWorkflowIdExists() {
        WorkflowProperties props = new WorkflowProperties();
        WorkflowProperties.CatalogEntry entry = new WorkflowProperties.CatalogEntry();
        entry.setId("knowledge-qa");
        props.setCatalog(List.of(entry));
        props.getDefinitions().put("knowledge-qa", new WorkflowProperties.WorkflowDefinitionProps());

        WorkflowCatalog catalog = new WorkflowCatalog(props);
        assertThat(catalog.isKnownWorkflow("knowledge-qa")).isTrue();
        assertThat(catalog.isKnownWorkflow("missing")).isFalse();
    }

    @Test
    void sanitizeUnknownWorkflowFallsBackToReact() {
        WorkflowProperties props = new WorkflowProperties();
        props.setCatalog(List.of());
        WorkflowCatalog catalog = new WorkflowCatalog(props);

        ExecutionPlan plan = new ExecutionPlan(
                ExecutionMode.WORKFLOW, "unknown", java.util.Map.of(), "test");

        ExecutionPlan sanitized = catalog.sanitize(plan);
        assertThat(sanitized.mode()).isEqualTo(ExecutionMode.REACT);
    }

    @Test
    void renderIntoClassifierReplacesPlaceholder() {
        WorkflowProperties props = new WorkflowProperties();
        WorkflowProperties.CatalogEntry entry = new WorkflowProperties.CatalogEntry();
        entry.setId("knowledge-qa");
        entry.setDesc("查制度");
        props.setCatalog(List.of(entry));

        WorkflowCatalog catalog = new WorkflowCatalog(props);
        String prompt = catalog.renderIntoClassifier("目录：\n{{workflow-catalog}}\n结束");

        assertThat(prompt).contains("knowledge-qa").doesNotContain("{{workflow-catalog}}");
    }
}
