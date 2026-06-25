package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.audit.AuditEvent;
import com.sunshine.orchestrator.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PlanExecutionAuditServiceTest {

    @Mock
    private AuditPublisher auditPublisher;
    @InjectMocks
    private PlanExecutionAuditService service;

    @Test
    void created_publishesPlanCreatedEvent() {
        service.created("c1", "m1", "u1", "default", "plan-1");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("plan.created");
        assertThat(event.payloadJson()).contains("\"planId\":\"plan-1\"");
    }

    @Test
    void completed_publishesTerminalStatus() {
        service.completed("c1", "m1", "u1", "default", "plan-1", "completed_with_errors");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("plan.completed");
        assertThat(captor.getValue().payloadJson()).contains("\"terminalStatus\":\"completed_with_errors\"");
    }
}
