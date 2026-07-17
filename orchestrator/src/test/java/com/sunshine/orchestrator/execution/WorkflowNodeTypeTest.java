package com.sunshine.orchestrator.execution;

import com.sunshine.common.workflow.WorkflowNodeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 编排侧仍依赖 common SSOT；完整断言见 sunshine-common WorkflowNodeTypeTest */
class WorkflowNodeTypeTest {

    @Test
    void orchConsumesCommonSsot() {
        assertThat(WorkflowNodeType.RAG.id()).isEqualTo("rag");
        assertThat(WorkflowNodeType.planExecTypeIds()).doesNotContain("start");
        assertThat(WorkflowNodeType.studioTypeIds()).contains("start");
    }
}
