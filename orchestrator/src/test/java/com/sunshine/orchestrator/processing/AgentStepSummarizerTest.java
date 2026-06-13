package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.ProcessingStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStepSummarizerTest {

    @Test
    void afterFor_zeroHits_mentionsQuery() {
        ProcessingTimelineSession session = new ProcessingTimelineSession();
        session.bindUserQuery("我怎么请假");
        session.pending("rag", "rag");
        session.start("rag", "rag");
        session.complete("rag", "命中 0 条");

        assertThat(AgentStepSummarizer.afterFor(session, "命中 0 条", "我怎么请假"))
                .contains("请假")
                .contains("通用知识");
    }

    @Test
    void afterFor_withHits_mentionsDocumentCount() {
        assertThat(AgentStepSummarizer.afterFor(null, "命中 3 条", "考勤制度"))
                .contains("3")
                .contains("考勤制度");
    }
}
