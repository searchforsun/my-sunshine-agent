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
        timeline.setIntent("【意图识别前】短问句补全");
        timeline.setRag("【知识库检索前】优化检索词");
        timeline.setHyde("【HyDE 检索前】假想制度片段");
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
                .startsWith("【知识库检索前】优化检索词")
                .contains("改写前：我今天打车了")
                .contains("改写后：因公打车报销流程 交通费制度")
                .contains("耗时：2568ms");
    }

    @Test
    void hydeTimelineDetailUsesHydeLabel() {
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of(
                "hyde", "我今天打车了", "员工因公外出产生的交通费用", 3021L);
        assertThat(outcome.timelineDetail())
                .startsWith("【HyDE 检索前】假想制度片段")
                .contains("HyDE：");
    }

    @Test
    void emptyRecallSkippedStillShowsInTimeline() {
        AgentRewriteProperties props = new AgentRewriteProperties();
        AgentRewriteProperties.Timeline timeline = new AgentRewriteProperties.Timeline();
        timeline.setEmptyRecall("【零命中二次检索】生成替代 query");
        props.setTimeline(timeline);
        RewriteTimelineLabels.bind(props);
        QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped("empty-recall", "我明天要打车", 800L);
        assertThat(skipped.timelineDetail())
                .contains("【零命中二次检索】")
                .contains("未生成有效替代 query");
        RewriteTimelineLabels.bind(null);
    }
}
