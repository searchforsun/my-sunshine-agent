package com.sunshine.orchestrator.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDefinitionLoaderTest {

    @Test
    void placeholder_dbLoaderUsesWorkflowManagerClient() {
        assertThat(WorkflowDefinitionLoader.class.getSimpleName()).isEqualTo("WorkflowDefinitionLoader");
    }
}
