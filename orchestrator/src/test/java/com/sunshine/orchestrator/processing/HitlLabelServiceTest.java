package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HitlLabelServiceTest {

    @BeforeEach
    void setUp() {
        HitlLabels.bind(new HitlLabelService(new AgentPromptProperties()));
    }

    @AfterEach
    void tearDown() {
        HitlLabels.bind(null);
    }

    @Test
    void readsDefaultTimelineConfig() {
        assertThat(HitlLabels.pending("审批 OA 任务")).isEqualTo("将调用工具 审批 OA 任务");
        assertThat(HitlLabels.awaiting()).isEqualTo("等待用户确认执行写操作");
        assertThat(HitlLabels.approved("审批 OA 任务")).isEqualTo("用户已确认，正在调用 审批 OA 任务");
        assertThat(HitlLabels.denied()).isEqualTo("用户取消调用");
        assertThat(HitlLabels.skippedAfter()).isEqualTo("用户取消调用，已跳过");
    }
}
