package com.sunshine.orchestrator.rewrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentRewriteProperties;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.prompt.PromptCatalogEntry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.prompt.PromptCatalogSnapshot;
import com.sunshine.orchestrator.registry.ModelCapabilities;
import com.sunshine.orchestrator.registry.ModelCatalogDefinition;
import com.sunshine.orchestrator.registry.ModelCatalogScene;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryRewriteServiceTest {
    private QueryRewriteService service;
    private AgentRewriteProperties props;
    private PromptCatalogHolder catalogHolder;
    private ModelSceneResolver modelSceneResolver;

    @BeforeEach
    void setUp() {
        props = new AgentRewriteProperties();
        catalogHolder = seedHolder("rewrite-intent-stub", "rewrite-planner-stub");
        modelSceneResolver = seedResolver();
        service = new QueryRewriteService(props, catalogHolder,
                mock(com.sunshine.orchestrator.client.LlmGatewayClient.class),
                modelSceneResolver,
                new ObjectMapper());
    }

    @Test
    void parseSingleQueryFromJson() {
        assertThat(service.parseSingleQuery("{\"query\":\"公司差旅费报销管理制度\"}", "报差旅"))
                .isEqualTo("公司差旅费报销管理制度");
    }

    @Test
    void shouldRewriteIntentOnlyWhenShort() {
        props.getIntent().setEnabled(true);
        props.getIntent().setMaxChars(8);
        assertThat(service.shouldRewriteIntent("报销")).isTrue();
        assertThat(service.shouldRewriteIntent("请问青松假有多少天、怎么申请")).isFalse();
    }

    @Test
    void rewriteForIntentSkipsLongQuery() {
        props.getIntent().setEnabled(true);
        assertThat(service.rewriteForIntent("请问青松假有多少天、怎么申请")).isEqualTo("请问青松假有多少天、怎么申请");
    }

    @Test
    void rewriteForIntentCallsLlm() {
        props.getIntent().setEnabled(true);
        catalogHolder = seedHolder("test intent prompt", "rewrite-planner-stub");
        var llm = mock(com.sunshine.orchestrator.client.LlmGatewayClient.class);
        when(llm.complete(anyString(), isNull(), anyString(), anyString()))
                .thenReturn("{\"query\":\"查询待审批报销消息列表\"}");
        service = new QueryRewriteService(props, catalogHolder, llm, modelSceneResolver, new ObjectMapper());
        assertThat(service.rewriteForIntent("待审批")).isEqualTo("查询待审批报销消息列表");
    }

    @Test
    void rewriteForIntentIncludesConversationContext() {
        props.getIntent().setEnabled(true);
        catalogHolder = seedHolder("test intent prompt", "rewrite-planner-stub");
        var llm = mock(com.sunshine.orchestrator.client.LlmGatewayClient.class);
        when(llm.complete(anyString(), isNull(), anyString(), anyString()))
                .thenReturn("{\"query\":\"查询第一条待审批报销单详情\"}");
        service = new QueryRewriteService(props, catalogHolder, llm, modelSceneResolver, new ObjectMapper());
        AssembledContext memory = new AssembledContext("", "", List.of(), List.of(
                        new ChatTurn("user", "查待审批报销"),
                        new ChatTurn("assistant", "共有3条待审批")), "");
        assertThat(service.rewriteForIntent("那第一条", null, memory).effectiveQuery())
                .isEqualTo("查询第一条待审批报销单详情");
        verify(llm).complete(
                eq("deepseek-v4-flash"),
                isNull(),
                eq("test intent prompt"),
                org.mockito.ArgumentMatchers.argThat(user ->
                        user.contains("近期对话：")
                                && user.contains("查待审批报销")
                                && user.contains("用户输入：那第一条")));
    }

    @Test
    void rewriteForPlannerCallsLlm() {
        props.plannerOrDefault().setEnabled(true);
        catalogHolder = seedHolder("rewrite-intent-stub", "test planner prompt");
        var llm = mock(com.sunshine.orchestrator.client.LlmGatewayClient.class);
        when(llm.complete(anyString(), isNull(), anyString(), anyString()))
                .thenReturn("{\"query\":\"先检索差旅制度，再查待审批报销并做合规分析\"}");
        service = new QueryRewriteService(props, catalogHolder, llm, modelSceneResolver, new ObjectMapper());
        assertThat(service.rewriteForPlanner("先查制度再查报销"))
                .isEqualTo("先检索差旅制度，再查待审批报销并做合规分析");
    }

    private static PromptCatalogHolder seedHolder(String intent, String planner) {
        PromptCatalogHolder holder = new PromptCatalogHolder();
        holder.replace(PromptCatalogSnapshot.of(1L, List.of(
                new PromptCatalogEntry("rewrite.intent", "rewrite", "i", true, 0, 1, intent, null),
                new PromptCatalogEntry("rewrite.planner", "rewrite", "p", true, 0, 1, planner, null))));
        return holder;
    }

    private static ModelSceneResolver seedResolver() {
        ModelSceneResolver resolver = new ModelSceneResolver(
                new ObjectMapper(), WebClient.builder(), "http://localhost", "default");
        resolver.replaceSnapshotForTest(
                List.of(new ModelCatalogDefinition(
                        "deepseek-v4-flash", "p", "flash", 128000, 8192, "cl100k_base",
                        ModelCapabilities.defaults(), null, true, true, 0)),
                List.of(
                        new ModelCatalogScene("rewrite.intent", "deepseek-v4-flash", null, Map.of(), true),
                        new ModelCatalogScene("rewrite.planner", "deepseek-v4-flash", null, Map.of(), true),
                        new ModelCatalogScene("default", "deepseek-v4-flash", null, Map.of(), true)));
        return resolver;
    }
}
