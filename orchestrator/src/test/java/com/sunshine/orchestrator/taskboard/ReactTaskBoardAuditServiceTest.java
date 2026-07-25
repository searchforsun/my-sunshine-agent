package com.sunshine.orchestrator.taskboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.audit.AuditEvent;
import com.sunshine.orchestrator.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReactTaskBoardAuditServiceTest {

    @Mock
    private AuditPublisher auditPublisher;
    @Mock
    private ReactTaskBoardRepository repository;

    private ReactTaskBoardAuditService service;

    @BeforeEach
    void setUp() {
        service = new ReactTaskBoardAuditService(auditPublisher, repository);
    }

    @Test
    void persistFinal_savesEntityAndPublishesFinalEvent() {
        when(repository.findByMessageId("msg-2")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ReactTaskBoardState state = new ReactTaskBoardState(
                "board-2", "msg-2", 3, 2000L,
                List.of(
                        new TaskBoardItemView("t1", "步骤1", "completed"),
                        new TaskBoardItemView("t2", "步骤2", "completed")));

        service.persistFinal(state);

        ArgumentCaptor<ReactTaskBoardEntity> entityCaptor = ArgumentCaptor.forClass(ReactTaskBoardEntity.class);
        verify(repository).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getMessageId()).isEqualTo("msg-2");
        assertThat(entityCaptor.getValue().getRevision()).isEqualTo(3);
        assertThat(entityCaptor.getValue().getConversationId()).isEqualTo("");

        ArgumentCaptor<AuditEvent> eventCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo("react.taskboard.final");
    }

    @Test
    void findByMessageId_mapsEntityToView() throws Exception {
        ReactTaskBoardEntity entity = new ReactTaskBoardEntity();
        entity.setId("board-4");
        entity.setMessageId("msg-4");
        entity.setConversationId("conv-4");
        entity.setTenantId("default");
        entity.setUserId("u4");
        entity.setRevision(1);
        entity.setItemsJson(new ObjectMapper().writeValueAsString(
                List.of(new TaskBoardItemView("t1", "任务", "pending"))));
        entity.setCreatedAt(java.time.Instant.parse("2026-07-07T00:00:00Z"));
        entity.setUpdatedAt(java.time.Instant.parse("2026-07-07T00:01:00Z"));
        when(repository.findByMessageId("msg-4")).thenReturn(Optional.of(entity));

        ReactTaskBoardAuditView view = service.findByMessageId("msg-4").orElseThrow();

        assertThat(view.boardId()).isEqualTo("board-4");
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).content()).isEqualTo("任务");
    }
}
