package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class IntentLabelServiceTest {

    private IntentLabelService intentLabelService;
    private TimelineStepLabelService timelineStepLabelService;
    private ThinkStepLabelService thinkStepLabelService;

    @BeforeEach
    void setUp() {
        TimelinePromptCatalog timelineCatalog = TimelinePromptCatalog.withDefaults();
        timelineStepLabelService = new TimelineStepLabelService(timelineCatalog);
        thinkStepLabelService = new ThinkStepLabelService(timelineCatalog);
        intentLabelService = new IntentLabelService(timelineCatalog);
        IntentLabels.bind(intentLabelService);
        TimelineLabels.bind(timelineStepLabelService);
        TimelineStepLabels.bind(timelineStepLabelService);
        ThinkStepLabels.bind(thinkStepLabelService);
    }

    @AfterEach
    void tearDown() {
        IntentLabels.bind(null);
        TimelineLabels.bind(null);
        TimelineStepLabels.bind(null);
        ThinkStepLabels.bind(null);
    }

    @Test
    void intentAfterForPlan_unifiedStatus_forAnyMode() {
        for (ExecutionMode mode : ExecutionMode.values()) {
            ExecutionPlan plan = new ExecutionPlan(
                    mode, mode == ExecutionMode.WORKFLOW ? "knowledge-qa" : null, Map.of(), "test");
            assertThat(intentLabelService.intentAfterForPlan("公司考勤制度是什么？", plan))
                    .isEqualTo("已完成意图识别");
        }
    }

    @Test
    void intentAfterForPlan_nullPlan_unifiedStatus() {
        assertThat(intentLabelService.intentAfterForPlan("公司考勤制度是什么？", null))
                .isEqualTo("已完成意图识别");
    }

    @Test
    void intentAfterSummary_unifiedStatus_ignoresDetail() {
        assertThat(intentLabelService.intentAfterSummary("公司考勤制度是什么？", "知识库问答"))
                .isEqualTo("已完成意图识别");
        assertThat(intentLabelService.intentAfterSummary("公司考勤制度是什么？", null))
                .isEqualTo("已完成意图识别");
    }

    @Test
    void stepSummarizer_intentActive_noLegacyKnowledgeRoutingPhrase() {
        String after = StepSummarizer.active("intent", "我有哪些待审批报销");
        assertThat(after).isEqualTo("正在识别用户意图...");
        assertThat(after).doesNotContain("查阅知识库").doesNotContain("直接回答");
    }

    @Test
    void stepSummarizer_intentAfter_unifiedStatus() {
        String after = StepSummarizer.after("intent", "公司考勤制度是什么？", "知识库问答");
        assertThat(after).isEqualTo("已完成意图识别");
        assertThat(after).doesNotContain("知识库问答").doesNotContain("流程处理");
    }
}
