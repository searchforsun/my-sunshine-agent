package com.sunshine.rag.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.client.LlmGatewayClient;
import com.sunshine.rag.config.RagRewriteProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class QueryRewritePipelineTest {
    private QueryRewritePipeline pipeline;

    @BeforeEach
    void setUp() {
        RagRewriteProperties props = new RagRewriteProperties();
        props.getEmptyRecall().setEnabled(true);
        props.getEmptyRecall().setMaxAlternatives(2);
        props.getEmptyRecall().setSystemPrompt("gen %d queries");
        pipeline = new QueryRewritePipeline(props, mock(LlmGatewayClient.class), new ObjectMapper());
    }

    @Test
    void parseQueriesFromJson() {
        List<String> queries = pipeline.parseQueries(
                "{\"queries\":[\"公司报销制度 差旅\",\"差旅费报销流程\"]}",
                "报一下差旅",
                2);
        assertThat(queries).containsExactly("公司报销制度 差旅", "差旅费报销流程");
    }

    @Test
    void parseQueriesSkipsOriginal() {
        List<String> queries = pipeline.parseQueries(
                "{\"queries\":[\"报一下差旅\",\"报销管理办法\"]}",
                "报一下差旅",
                2);
        assertThat(queries).containsExactly("报销管理办法");
    }

    @Test
    void parseSingleQueryFromJson() {
        String q = pipeline.parseSingleQuery("{\"query\":\"优化后的问句\"}", "原问");
        assertThat(q).isEqualTo("优化后的问句");
    }

    @Test
    void parseHydeDocumentFromJson() {
        String doc = pipeline.parseHydeDocument("{\"document\":\"员工年假按工龄累计\"}", 480);
        assertThat(doc).isEqualTo("员工年假按工龄累计");
    }

    @Test
    void rewriteForRagSkippedWhenDisabled() {
        RagRewriteProperties props = new RagRewriteProperties();
        props.getRag().setEnabled(false);
        QueryRewritePipeline disabled = new QueryRewritePipeline(props, mock(LlmGatewayClient.class), new ObjectMapper());
        QueryRewriteOutcome outcome = disabled.rewriteForRag("问");
        assertThat(outcome.applied()).isFalse();
    }
}
