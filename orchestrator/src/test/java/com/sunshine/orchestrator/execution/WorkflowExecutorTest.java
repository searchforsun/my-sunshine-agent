package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.handler.AnswerNodeHandler;
import com.sunshine.orchestrator.execution.handler.LlmNodeHandler;
import com.sunshine.orchestrator.execution.handler.RagNodeHandler;
import com.sunshine.orchestrator.execution.handler.StartNodeHandler;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowExecutorTest {

    @Mock
    private WorkflowDefinitionLoader loader;

    @Mock
    private NodeHandlerRegistry registry;

    @Mock
    private LegacyWorkflowExecutor legacyWorkflowExecutor;

    @Mock
    private StartNodeHandler startNodeHandler;

    @Mock
    private RagNodeHandler ragNodeHandler;

    @Mock
    private LlmNodeHandler llmNodeHandler;

    @Mock
    private AnswerNodeHandler answerNodeHandler;

    private WorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        when(registry.require("start")).thenReturn(startNodeHandler);
        when(registry.require("rag")).thenReturn(ragNodeHandler);
        when(registry.require("llm")).thenReturn(llmNodeHandler);
        when(registry.require("answer")).thenReturn(answerNodeHandler);

        when(startNodeHandler.run(any(), any(), any()))
                .thenReturn(Mono.just(NodeResult.ok(Map.of("userQuery", "请假制度"))));
        when(ragNodeHandler.run(any(), any(), any()))
                .thenReturn(Mono.just(NodeResult.ok(Map.of("output", "rag-hit", "detail", "命中 1 条"))));
        when(llmNodeHandler.run(any(), any(), any()))
                .thenReturn(Mono.just(NodeResult.ok(Map.of("answer", "请假需提前申请"))));
        when(answerNodeHandler.run(any(), any(), any()))
                .thenReturn(Mono.just(NodeResult.withContent(
                        Map.of("answer", "请假需提前申请"),
                        List.of(StreamToken.content("请假需提前申请")))));

        executor = new WorkflowExecutor(loader, registry, legacyWorkflowExecutor);
    }

    @Test
    void runsLinearWorkflow() {
        WorkflowDefinition def = WorkflowDefinition.from("knowledge-qa", List.of(
                new NodeSpec("start", "start", Map.of()),
                new NodeSpec("rag", "rag", Map.of("topK", "3")),
                new NodeSpec("llm", "llm", Map.of("prompt", "test")),
                new NodeSpec("answer", "answer", Map.of())
        ), List.of("start", "rag", "llm", "answer"));

        when(loader.load("knowledge-qa")).thenReturn(java.util.Optional.of(def));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "m1", "请假制度是什么", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "knowledge-qa", Map.of(), "查制度"));

        List<StreamToken> tokens = executor.execute(ctx).collectList().block();
        assertThat(tokens).isNotNull().isNotEmpty();
        assertThat(tokens.stream().anyMatch(StreamToken::isStep)).isTrue();
        assertThat(tokens.stream().anyMatch(StreamToken::isContent)).isTrue();
    }

    @Test
    void fallsBackToLegacyWhenDefinitionMissing() {
        when(loader.load("unknown")).thenReturn(java.util.Optional.empty());
        when(legacyWorkflowExecutor.execute(any())).thenReturn(Flux.just(StreamToken.content("legacy")));

        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "c1", "m1", "test", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.WORKFLOW, "unknown", Map.of(), "test"));

        List<StreamToken> tokens = executor.execute(ctx).collectList().block();
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).text()).isEqualTo("legacy");
    }
}
