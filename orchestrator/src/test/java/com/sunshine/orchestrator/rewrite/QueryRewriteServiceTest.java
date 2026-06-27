package com.sunshine.orchestrator.rewrite;



import com.fasterxml.jackson.databind.ObjectMapper;

import com.sunshine.orchestrator.config.AgentRewriteProperties;

import com.sunshine.orchestrator.conversation.ChatTurn;

import com.sunshine.orchestrator.memory.MemoryContext;

import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;



import java.util.List;



import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.verify;

import static org.mockito.Mockito.when;



class QueryRewriteServiceTest {



    private QueryRewriteService service;

    private AgentRewriteProperties props;



    @BeforeEach

    void setUp() {

        props = new AgentRewriteProperties();

        props.getEmptyRecall().setEnabled(true);
        props.getEmptyRecall().setMaxAlternatives(2);
        props.getEmptyRecall().setSystemPrompt("gen %d queries");

        service = new QueryRewriteService(props, mock(com.sunshine.orchestrator.client.LlmGatewayClient.class),

                new ObjectMapper());

    }



    @Test

    void parseQueriesFromJson() {

        List<String> queries = service.parseQueries(

                "{\"queries\":[\"公司报销制度 差旅\",\"差旅费报销流程\"]}",

                "报一下差旅",

                2);

        assertThat(queries).containsExactly("公司报销制度 差旅", "差旅费报销流程");

    }



    @Test

    void parseQueriesSkipsOriginal() {

        List<String> queries = service.parseQueries(

                "{\"queries\":[\"报一下差旅\",\"报销管理办法\"]}",

                "报一下差旅",

                2);

        assertThat(queries).containsExactly("报销管理办法");

    }



    @Test

    void parseSingleQueryFromJson() {

        assertThat(service.parseSingleQuery("{\"query\":\"公司差旅费报销管理制度\"}", "报差旅"))

                .isEqualTo("公司差旅费报销管理制度");

    }



    @Test

    void rewriteForRagDisabledReturnsOriginal() {

        props.getRag().setEnabled(false);

        assertThat(service.rewriteForRag("报差旅")).isEqualTo("报差旅");

    }



    @Test

    void rewriteForRagCallsLlm() {

        props.getRag().setEnabled(true);
        props.getRag().setSystemPrompt("test rag prompt");

        var llm = mock(com.sunshine.orchestrator.client.LlmGatewayClient.class);

        when(llm.complete(anyString(), anyString(), anyString()))

                .thenReturn("{\"query\":\"公司差旅费报销管理办法\"}");

        service = new QueryRewriteService(props, llm, new ObjectMapper());

        assertThat(service.rewriteForRag("报差旅")).isEqualTo("公司差旅费报销管理办法");

    }



    @Test

    void shouldRewriteIntentOnlyWhenShort() {

        props.getIntent().setEnabled(true);
        props.getIntent().setSystemPrompt("test intent prompt");

        props.getIntent().setMaxChars(8);

        assertThat(service.shouldRewriteIntent("报销")).isTrue();

        assertThat(service.shouldRewriteIntent("请问年假可以请几天")).isFalse();

    }



    @Test

    void rewriteForIntentSkipsLongQuery() {

        props.getIntent().setEnabled(true);
        props.getIntent().setSystemPrompt("test intent prompt");

        assertThat(service.rewriteForIntent("请问年假可以请几天")).isEqualTo("请问年假可以请几天");

    }



    @Test

    void rewriteForIntentCallsLlm() {

        props.getIntent().setEnabled(true);
        props.getIntent().setSystemPrompt("test intent prompt");

        var llm = mock(com.sunshine.orchestrator.client.LlmGatewayClient.class);

        when(llm.complete(anyString(), anyString(), anyString()))

                .thenReturn("{\"query\":\"查询待审批报销消息列表\"}");

        service = new QueryRewriteService(props, llm, new ObjectMapper());

        assertThat(service.rewriteForIntent("待审批")).isEqualTo("查询待审批报销消息列表");

    }

    @Test
    void rewriteForIntentIncludesConversationContext() {
        props.getIntent().setEnabled(true);
        props.getIntent().setSystemPrompt("test intent prompt");
        var llm = mock(com.sunshine.orchestrator.client.LlmGatewayClient.class);
        when(llm.complete(anyString(), anyString(), anyString()))
                .thenReturn("{\"query\":\"查询第一条待审批报销单详情\"}");
        service = new QueryRewriteService(props, llm, new ObjectMapper());
        MemoryContext memory = new MemoryContext(
                "",
                "",
                List.of(
                        new ChatTurn("user", "查待审批报销"),
                        new ChatTurn("assistant", "共有3条待审批")));
        assertThat(service.rewriteForIntent("那第一条", null, memory).effectiveQuery())
                .isEqualTo("查询第一条待审批报销单详情");
        verify(llm).complete(
                eq("deepseek-v4-flash"),
                eq("test intent prompt"),
                org.mockito.ArgumentMatchers.argThat(user ->
                        user.contains("近期对话：")
                                && user.contains("查待审批报销")
                                && user.contains("用户输入：那第一条")));
    }



    @Test

    void rewriteEmptyRecallDisabledReturnsEmpty() {

        props.getEmptyRecall().setEnabled(false);

        assertThat(service.rewriteEmptyRecall("test")).isEmpty();

    }



    @Test

    void rewriteEmptyRecallCallsLlm() {

        var llm = mock(com.sunshine.orchestrator.client.LlmGatewayClient.class);

        when(llm.complete(anyString(), anyString(), anyString()))

                .thenReturn("{\"queries\":[\"公司请假流程规范 病假材料\"]}");

        service = new QueryRewriteService(props, llm, new ObjectMapper());

        List<String> out = service.rewriteEmptyRecall("病假要啥");

        assertThat(out).containsExactly("公司请假流程规范 病假材料");

    }

    @Test
    void parseHydeDocumentFromJson() {
        String doc = service.parseHydeDocument(
                "{\"document\":\"员工出差须提前提交审批单，并保留交通与住宿发票。\"}",
                480);
        assertThat(doc).contains("出差");
    }

    @Test
    void hydeForRagDisabledReturnsSkipped() {
        props.getRag().getHyde().setEnabled(false);
        QueryRewriteOutcome out = service.hydeForRag("报差旅");
        assertThat(out.applied()).isFalse();
        assertThat(out.scenario()).isEqualTo("hyde");
    }

    @Test
    void hydeForRagCallsLlm() {
        props.getRag().getHyde().setEnabled(true);
        props.getRag().getHyde().setSystemPrompt("test hyde prompt");
        var llm = mock(com.sunshine.orchestrator.client.LlmGatewayClient.class);
        when(llm.complete(anyString(), anyString(), anyString()))
                .thenReturn("{\"document\":\"差旅费报销须附出差审批单与合规发票。\"}");
        service = new QueryRewriteService(props, llm, new ObjectMapper());
        QueryRewriteOutcome out = service.hydeForRag("报差旅");
        assertThat(out.applied()).isTrue();
        assertThat(out.rewrittenQuery()).contains("差旅");
    }

    @Test
    void rewriteForPlannerCallsLlm() {
        props.plannerOrDefault().setEnabled(true);
        props.plannerOrDefault().setSystemPrompt("test planner prompt");
        var llm = mock(com.sunshine.orchestrator.client.LlmGatewayClient.class);
        when(llm.complete(anyString(), anyString(), anyString()))
                .thenReturn("{\"query\":\"先检索差旅制度，再查待审批报销并做合规分析\"}");
        service = new QueryRewriteService(props, llm, new ObjectMapper());
        assertThat(service.rewriteForPlanner("先查制度再查报销"))
                .isEqualTo("先检索差旅制度，再查待审批报销并做合规分析");
    }

}


