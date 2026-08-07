package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.client.RagContextFormatter;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.rag.DefaultKbResolver;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.processing.TimelineLabelTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagNodeHandlerTest {

    @Mock
    private RagClient ragClient;

    @Mock
    private DefaultKbResolver defaultKbResolver;

    @Mock
    private RagContextFormatter ragContextFormatter;

    @InjectMocks
    private RagNodeHandler ragNodeHandler;

    @BeforeEach
    void bindTimelineLabels() {
        TimelineLabelTestSupport.bindDefaults();
    }

    @AfterEach
    void unbindTimelineLabels() {
        TimelineLabelTestSupport.unbind();
    }

    @Test
    void buildSearchQuery_appendsContextWhenBothPresent() {
        assertThat(RagNodeHandler.buildSearchQuery("用户问", "上游材料"))
                .isEqualTo("用户问\n\n上游材料");
    }

    @Test
    void buildSearchQuery_returnsContextWhenQueryBlank() {
        assertThat(RagNodeHandler.buildSearchQuery("", "仅上游"))
                .isEqualTo("仅上游");
    }

    @Test
    void run_usesResolvedQueryAndContext() {
        when(defaultKbResolver.resolve("default", null))
                .thenReturn(Mono.just("default"));
        when(ragContextFormatter.formatAgentContext(any())).thenReturn("");
        when(ragClient.searchKnowledge(eq("年假几天\n\n待办列表"), eq(null), eq("default"), eq("default"), eq(null), eq(true)))
                .thenReturn(Mono.just(new RagClient.RagSearchResult(List.of(), "q", List.of())));

        WorkflowContext ctx = new WorkflowContext();
        ExecutionStreamContext streamCtx = new ExecutionStreamContext(
                "c1", "msg-1", "年假几天", AssembledContext.empty(),
                null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "test"));
        NodeSpec spec = new NodeSpec(
                "rag-1",
                "rag",
                Map.of("query", "年假几天", "context", "待办列表"),
                "知识检索");

        ragNodeHandler.run(spec, ctx, streamCtx).block();

        verify(ragClient).searchKnowledge(eq("年假几天\n\n待办列表"), eq(null), eq("default"), eq("default"), eq(null), eq(true));
    }

    @Test
    void buildOkResultContainsStructuredHits() {
        var hits = List.of(
                new RagClient.RagHit("doc1.md", "content1", 0.9f),
                new RagClient.RagHit("doc2.md", "content2", 0.8f));
        NodeResult result = RagNodeHandler.buildOkResultForTest(hits);
        TypedValue hitsVal = result.safeOutputs().get("hits");
        assertThat(hitsVal).isInstanceOf(TypedValue.JsonArray.class);
        assertThat(((TypedValue.JsonArray) hitsVal).node().size()).isEqualTo(2);
        assertThat(result.safeOutputs().get("hitCount").render()).isEqualTo("2");
    }
}
