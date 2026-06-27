package com.sunshine.orchestrator.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionPlanStoreTest {

    @Mock
    private ExecutionPlanRepository repository;
    @Mock
    private ConversationService conversationService;
    @Mock
    private PlanJsonParser planJsonParser;

    private ExecutionPlanStore store;

    @BeforeEach
    void setUp() {
        AgentPromptProperties props = new AgentPromptProperties();
        store = new ExecutionPlanStore(repository, new PlanJsonCodec(new ObjectMapper()),
                planJsonParser, props, conversationService);
    }

    @Test
    void createDraft_persistsAndLinksMessage() {
        ExecutionStreamContext ctx = new ExecutionStreamContext(
                "conv-1", "msg-1", "query", MemoryContext.empty(),
                null, null, null, "u1", "default",
                new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "test"));
        PlanJson planJson = new PlanJson(null, "跨领域", List.of(
                new PlanNode("n1", "llm", Map.of()),
                new PlanNode("ans", "answer", Map.of())), List.of());

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String planId = store.createDraft(ctx, planJson);

        assertThat(planId).isNotBlank();
        ArgumentCaptor<ExecutionPlanEntity> captor = ArgumentCaptor.forClass(ExecutionPlanEntity.class);
        verify(repository).save(captor.capture());
        ExecutionPlanEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("draft");
        assertThat(saved.getConversationId()).isEqualTo("conv-1");
        assertThat(saved.getMessageId()).isEqualTo("msg-1");
        assertThat(saved.getPlanJson()).contains("\"type\":\"llm\"");
    }

    @Test
    void markValidated_linksMessage() {
        ExecutionPlanEntity entity = new ExecutionPlanEntity();
        entity.setId("p1");
        entity.setStatus("draft");
        entity.setMessageId("msg-1");
        when(repository.findById("p1")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.markValidated("p1", new PlanJson("p1", "ok", List.of(), List.of()));

        verify(conversationService).linkMessageExecutionPlan("msg-1", "p1");
        assertThat(entity.getStatus()).isEqualTo("validated");
    }

    @Test
    void markRejected_setsTerminalStatus() {
        ExecutionPlanEntity entity = new ExecutionPlanEntity();
        entity.setId("p1");
        entity.setStatus("draft");
        when(repository.findById("p1")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.markRejected("p1", "非法节点");

        assertThat(entity.getStatus()).isEqualTo("rejected");
        assertThat(entity.getRejectReason()).isEqualTo("非法节点");
        assertThat(entity.getCompletedAt()).isNotNull();
    }

    @Test
    void appendNodeTrace_appendsJsonArray() {
        ExecutionPlanEntity entity = new ExecutionPlanEntity();
        entity.setId("p1");
        entity.setStatus("running");
        when(repository.findById("p1")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.upsertNodeTrace("p1", new PlanNodeTrace("n1", "rag", "completed", "命中 2 条", null, 1L, 2L));

        assertThat(entity.getExecutionTrace()).contains("\"nodeId\":\"n1\"");
        assertThat(entity.getExecutionTrace()).contains("\"status\":\"completed\"");
    }

    @Test
    void markPaused_acceptsValidatedStatus() {
        ExecutionPlanEntity entity = new ExecutionPlanEntity();
        entity.setId("p1");
        entity.setStatus("validated");
        when(repository.findById("p1")).thenReturn(Optional.of(entity));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        store.markPaused("p1", new WorkflowCheckpoint("n1", "{}", PausePhase.PLANNING, null));

        assertThat(entity.getStatus()).isEqualTo("paused");
        assertThat(entity.getPauseCheckpoint()).contains("\"pausePhase\":\"PLANNING\"");
    }

    @Test
    void findResumableForMessage_acceptsRunningWithCheckpoint() {
        ExecutionPlanEntity entity = new ExecutionPlanEntity();
        entity.setId("p1");
        entity.setStatus("running");
        entity.setPauseCheckpoint("{\"resumeNodeId\":\"approve\",\"pausePhase\":\"EXECUTING\"}");
        when(repository.findByMessageId("msg-1")).thenReturn(Optional.of(entity));

        assertThat(store.findResumableForMessage("msg-1")).isPresent();
    }

    @Test
    void inferPlanningResumeNodeId_returnsFirstBusinessNode() {
        ExecutionPlanEntity entity = new ExecutionPlanEntity();
        entity.setValidatedJson("{\"nodes\":[{\"id\":\"n1\",\"type\":\"llm\"}],"
                + "\"edges\":[{\"from\":\"start\",\"to\":\"n1\"}]}");
        PlanJson plan = new PlanJson("p1", "r",
                List.of(new PlanNode("n1", "llm", Map.of())),
                List.of(new PlanEdge("start", "n1")));
        when(planJsonParser.parse(entity.getValidatedJson())).thenReturn(plan);

        assertThat(store.inferPlanningResumeNodeId(entity)).isEqualTo("n1");
    }
}
