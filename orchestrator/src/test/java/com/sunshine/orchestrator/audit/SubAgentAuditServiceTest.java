package com.sunshine.orchestrator.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SubAgentAuditServiceTest {

    @Mock
    private AuditPublisher auditPublisher;
    @InjectMocks
    private SubAgentAuditService service;

    @Test
    void subAgentRun_publishesPayloadWithRunIdAndSkill() {
        service.subAgentRun(
                "c1", "m1", "u1", "default", "plan-1", "n3", "run-abc",
                "compliance-check", List.of("tool-list_finance_messages"), "合规风险 2 项", "ok");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.eventType()).isEqualTo("sub_agent_run");
        assertThat(event.status()).isEqualTo("ok");
        assertThat(event.payloadJson()).contains("\"runId\":\"run-abc\"");
        assertThat(event.payloadJson()).contains("\"skillId\":\"compliance-check\"");
        assertThat(event.payloadJson()).contains("\"planId\":\"plan-1\"");
    }
}
