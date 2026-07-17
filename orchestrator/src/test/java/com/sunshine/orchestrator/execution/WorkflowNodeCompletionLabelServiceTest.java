package com.sunshine.orchestrator.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowNodeCompletionLabelServiceTest {

    @Test
    void formatsCompletionTemplates() {
        WorkflowNodeCompletionLabelService service = new WorkflowNodeCompletionLabelService();
        assertThat(service.nodeComplete("检索知识库")).isEqualTo("检索知识库完成");
        assertThat(service.hitCount("3")).isEqualTo("命中 3 条");
        assertThat(service.skippedWithReason("用户取消")).isEqualTo("已跳过：用户取消");
        assertThat(service.retrySuccess(2)).isEqualTo("（第 2 次尝试成功）");
        assertThat(service.attemptFailed("超时")).isEqualTo("失败: 超时");
    }
}
