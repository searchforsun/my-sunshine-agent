package com.sunshine.orchestrator.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepSummarizerTest {

    @Test
    void intentAfter_knowledge_mentionsQuery() {
        String after = StepSummarizer.after("intent", "公司考勤制度是什么？", "知识库查询");
        assertThat(after).contains("公司考勤制度");
        assertThat(after).contains("知识库");
    }

    @Test
    void ragAfter_zeroHits_mentionsQuery() {
        String after = StepSummarizer.after("rag", "我怎么请假", "命中 0 条");
        assertThat(after).contains("请假");
        assertThat(after).contains("未找到");
    }

    @Test
    void ragAfter_withHits_mentionsCountAndQuery() {
        String after = StepSummarizer.after("rag", "考勤制度", "命中 3 条");
        assertThat(after).contains("3 条");
        assertThat(after).contains("考勤制度");
    }

    @Test
    void generateAfter_mentionsQuery() {
        String after = StepSummarizer.after("generate", "你好", null);
        assertThat(after).contains("你好");
        assertThat(after).contains("已完成");
    }

    @Test
    void before_active_includeUserQuery() {
        assertThat(StepSummarizer.before("intent", "测试问题"))
                .isEqualTo("阅读「测试问题」");
        assertThat(StepSummarizer.active("rag", "测试问题"))
                .contains("测试问题");
    }
}
