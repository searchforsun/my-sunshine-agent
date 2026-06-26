package com.sunshine.orchestrator.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.plan.dto.ExecutionPlanDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionPlanQueryServiceTest {

    @Mock
    private ExecutionPlanRepository repository;
    @Mock
    private ConversationService conversationService;

    private ExecutionPlanQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new ExecutionPlanQueryService(
                repository, conversationService, new PlanJsonCodec(new ObjectMapper()));
    }

    @Test
    void getDetail_returnsOwnedPlan() {
        ExecutionPlanEntity entity = sampleEntity();
        when(repository.findById("p1")).thenReturn(Optional.of(entity));

        ExecutionPlanDetailDto detail = queryService.getDetail("p1", "u1", "default");

        assertThat(detail.getId()).isEqualTo("p1");
        assertThat(detail.getStatus()).isEqualTo("completed");
        assertThat(detail.getPlan()).containsKey("nodes");
    }

    @Test
    void getDetail_rejectsOtherUser() {
        ExecutionPlanEntity entity = sampleEntity();
        entity.setUserId("other");
        when(repository.findById("p1")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> queryService.getDetail("p1", "u1", "default"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(OrchestratorErrorCode.EXECUTION_PLAN_NOT_FOUND);
    }

    @Test
    void listByConversation_checksOwnership() {
        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId("c1");
        when(conversationService.getOwned("c1", "u1", "default")).thenReturn(conv);
        when(repository.findByConversationIdOrderByCreatedAtDesc("c1"))
                .thenReturn(List.of(sampleEntity()));

        var list = queryService.listByConversation("c1", "u1", "default");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getId()).isEqualTo("p1");
        verify(conversationService).getOwned("c1", "u1", "default");
    }

    @Test
    void listNodeTraces_returnsTraceArray() {
        ExecutionPlanEntity entity = sampleEntity();
        entity.setExecutionTrace("""
                [{"nodeId":"n1","type":"rag","status":"completed","summary":"命中 2 条","startedAt":1,"endedAt":2}]
                """);
        when(repository.findById("p1")).thenReturn(Optional.of(entity));

        List<PlanNodeTrace> nodes = queryService.listNodeTraces("p1", "u1", "default");

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).nodeId()).isEqualTo("n1");
    }

    private static ExecutionPlanEntity sampleEntity() {
        ExecutionPlanEntity entity = new ExecutionPlanEntity();
        entity.setId("p1");
        entity.setConversationId("c1");
        entity.setMessageId("m1");
        entity.setUserId("u1");
        entity.setTenantId("default");
        entity.setStatus("completed");
        entity.setPlannerReason("跨领域");
        entity.setPlanJson("""
                {"planId":"p1","reason":"跨领域","nodes":[{"id":"n1","type":"rag"}],"edges":[]}
                """);
        entity.setCreatedAt(Instant.parse("2026-06-23T00:00:00Z"));
        return entity;
    }
}
