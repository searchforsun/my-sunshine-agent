package com.sunshine.orchestrator.rewrite;

import com.sunshine.orchestrator.config.AgentRewriteProperties;
import com.sunshine.orchestrator.processing.RewriteTimelineLabels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriteOutcomeTimelineTest {

    @BeforeEach
    void setUp() {
        AgentRewriteProperties props = new AgentRewriteProperties();
        AgentRewriteProperties.Timeline timeline = new AgentRewriteProperties.Timeline();
        timeline.setIntent("补全问句");
        timeline.setRag("优化检索词");
        timeline.setHyde("生成参考文档");
        props.setTimeline(timeline);
        RewriteTimelineLabels.bind(props);
    }

    @AfterEach
    void tearDown() {
        RewriteTimelineLabels.bind(null);
    }

    @Test
    void timelineDetailIncludesScenarioLabel() {
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of(
                "rag", "我今天打车了", "因公打车报销流程 交通费制度", 2568L);
        assertThat(outcome.timelineDetail())
                .startsWith("优化检索词")
                .contains("原问题：我今天打车了")
                .contains("优化后：因公打车报销流程 交通费制度")
                .contains("2.6s");
    }

    @Test
    void hydeTimelineDetailUsesHydeLabel() {
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of(
                "hyde", "我今天打车了", "员工因公外出产生的交通费用", 3021L);
        assertThat(outcome.timelineDetail())
                .startsWith("生成参考文档")
                .contains("参考文档：");
    }

    @Test
    void emptyRecallSkippedStillShowsInTimeline() {
        AgentRewriteProperties props = new AgentRewriteProperties();
        AgentRewriteProperties.Timeline timeline = new AgentRewriteProperties.Timeline();
        timeline.setEmptyRecall("换种方式再查");
        props.setTimeline(timeline);
        RewriteTimelineLabels.bind(props);
        QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped("empty-recall", "我明天要打车", 800L);
        assertThat(skipped.timelineDetail())
                .contains("换种方式再查")
                .contains("未能生成新的检索词");
        RewriteTimelineLabels.bind(null);
    }
}
