package com.sunshine.orchestrator.audit;

import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditPublisher auditPublisher;
    @Mock
    private ChatConversationRepository conversationRepository;
    @InjectMocks
    private AuditService auditService;

    @Test
    void auditAssistantMessage_publishesOnCompleted() {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setId("m1");
        msg.setConversationId("c1");
        msg.setRole("assistant");
        msg.setStatus("completed");
        msg.setContent("hello");
        msg.setIntent("simple");
        msg.setSteps("""
                [{"id":"intent","metadata":{"rewriteApplied":true,"rewriteLatencyMs":10,
                  "rewriteFrom":"待审批","rewriteTo":"查询待审批","rewriteScenario":"intent"}}]
                """);

        ChatConversationEntity conv = new ChatConversationEntity();
        conv.setId("c1");
        conv.setUserId("u1");
        conv.setTenantId("default");
        when(conversationRepository.findById("c1")).thenReturn(Optional.of(conv));

        auditService.auditAssistantMessage(msg);

        verify(auditPublisher).publish(argThat(event ->
                event.payloadJson() != null
                        && event.payloadJson().contains("\"rewriteApplied\":true")
                        && event.payloadJson().contains("\"rewriteLatencyMs\":10")));
    }

    @Test
    void auditAssistantMessage_skipsStreaming() {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setRole("assistant");
        msg.setStatus("streaming");
        msg.setUpdatedAt(Instant.now());

        auditService.auditAssistantMessage(msg);

        verify(auditPublisher, never()).publish(any());
    }
}
