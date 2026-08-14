package com.sunshine.orchestrator.rewrite;

import com.sunshine.orchestrator.processing.RewriteTimelineLabels;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRewriteOutcomeTimelineTest {

    @BeforeEach
    void setUp() {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        holder.replace(PromptCatalogSnapshot.of(1L, List.of(
                new PromptCatalogEntry("rewrite.timeline", "rewrite", "rewrite.timeline", true, 0, 1,
                        null, "{\"intent\":\"补全问句\"}"))));
        RewriteTimelineLabels.bind(new TimelinePromptCatalog(holder));
    }

    @AfterEach
    void tearDown() {
        RewriteTimelineLabels.bind(null);
    }

    @Test
    void timelineDetailUsesTraceScenarioLabelForRag() {
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of(
                "rag", "我今天打车了", "因公打车报销流程 交通费制度", 2568L, "优化检索词");
        assertThat(outcome.timelineDetail())
                .startsWith("优化检索词")
                .contains("原问题：我今天打车了")
                .contains("优化后：因公打车报销流程 交通费制度")
                .contains("2.6s");
    }

    @Test
    void hydeTimelineDetailUsesTraceScenarioLabel() {
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of(
                "hyde", "我今天打车了", "员工因公外出产生的交通费用", 3021L, "生成参考文档");
        assertThat(outcome.timelineDetail())
                .startsWith("生成参考文档")
                .contains("参考文档：");
    }

    @Test
    void emptyRecallSkippedUsesTraceScenarioLabel() {
        QueryRewriteOutcome skipped = QueryRewriteOutcome.skipped(
                "empty-recall", "我明天要打车", 800L, "换种方式再查");
        assertThat(skipped.timelineDetail())
                .contains("换种方式再查")
                .contains("未能生成新的检索词");
    }

    @Test
    void intentTimelineDetailUsesCatalogLabel() {
        QueryRewriteOutcome outcome = QueryRewriteOutcome.of("intent", "待审批", "查询待审批报销", 15L);
        assertThat(outcome.timelineDetail()).startsWith("补全问句");
    }
}
