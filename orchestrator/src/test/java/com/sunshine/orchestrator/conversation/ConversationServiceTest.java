package com.sunshine.orchestrator.conversation;

import com.sunshine.orchestrator.audit.AuditService;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import com.sunshine.orchestrator.sandbox.SandboxSessionLifecycle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private ChatConversationRepository conversationRepo;
    @Mock
    private ChatMessageRepository messageRepo;
    @Mock
    private AuditService auditService;
    @Mock
    private MessagePersistenceReconciler messagePersistenceReconciler;
    @Mock
    private SandboxSessionLifecycle sandboxSessionLifecycle;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        service = new ConversationService(
                conversationRepo, messageRepo, auditService,
                messagePersistenceReconciler, sandboxSessionLifecycle);
        lenient().when(messageRepo.save(any(ChatMessageEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(conversationRepo.findById(any())).thenReturn(Optional.empty());
    }

    private ChatMessageEntity persistedMessage(String id) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setId(id);
        msg.setConversationId("conv-1");
        msg.setSeq(1);
        msg.setRole("assistant");
        msg.setContent("旧正文");
        msg.setStatus(MessageStatus.STREAMING);
        return msg;
    }

    @Test
    @DisplayName("7 参 updateMessage 将 usageJson 写入消息")
    void updateMessageWithUsageJson_writesUsageColumn() {
        ChatMessageEntity msg = persistedMessage("m-usage");
        when(messageRepo.findById("m-usage")).thenReturn(Optional.of(msg));
        String usageJson = "{\"type\":\"usage\",\"messageUsage\":{\"inputTokens\":100,\"outputTokens\":50,\"llmCalls\":2}}";

        ChatMessageEntity saved = service.updateMessage(
                "m-usage", "终稿", null, MessageStatus.COMPLETED, "[]", "[]", usageJson);

        assertThat(saved.getUsageJson()).isEqualTo(usageJson);
        assertThat(saved.getStatus()).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    @DisplayName("6 参委托传 null：保留旧 usageJson 不覆写")
    void updateMessageWithoutUsageJson_keepsExistingUsage() {
        ChatMessageEntity msg = persistedMessage("m-keep");
        String existing = "{\"messageUsage\":{\"inputTokens\":9,\"outputTokens\":3,\"llmCalls\":1}}";
        msg.setUsageJson(existing);
        when(messageRepo.findById("m-keep")).thenReturn(Optional.of(msg));

        ChatMessageEntity saved = service.updateMessage(
                "m-keep", "续跑中", null, MessageStatus.STREAMING, null, null);

        assertThat(saved.getUsageJson()).isEqualTo(existing);
    }
}
