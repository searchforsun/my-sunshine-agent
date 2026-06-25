package com.sunshine.orchestrator.audit;

import com.sunshine.orchestrator.client.DesensitizeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolAuditServiceTest {

    @Mock
    private AuditPublisher auditPublisher;
    @Mock
    private DesensitizeClient desensitizeClient;
    @InjectMocks
    private ToolAuditService service;

    @Test
    void toolCall_publishesScrubbedParams() {
        when(desensitizeClient.scrub(anyString())).thenAnswer(inv -> inv.getArgument(0));
        service.toolCall(
                "c1", "m1", "u1", "default", "plan-1", "n2",
                "list_finance_messages", Map.of("status", "pending"), "共 3 条", "ok");
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("tool.call");
        assertThat(captor.getValue().payloadJson()).contains("\"toolId\":\"list_finance_messages\"");
        assertThat(captor.getValue().payloadJson()).contains("\"planId\":\"plan-1\"");
    }
}
