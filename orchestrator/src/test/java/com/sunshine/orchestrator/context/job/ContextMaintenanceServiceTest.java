package com.sunshine.orchestrator.context.job;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Entity;
import com.sunshine.orchestrator.context.l1.ConversationContextL1Repository;
import com.sunshine.orchestrator.context.l2.UserContextStateEntity;
import com.sunshine.orchestrator.context.l2.UserContextStateRepository;
import com.sunshine.orchestrator.context.l3.HistoryRagClient;
import com.sunshine.orchestrator.conversation.repo.ChatConversationRepository;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextMaintenanceServiceTest {

    @Mock
    private UserContextStateRepository l2Repository;
    @Mock
    private ConversationContextL1Repository l1Repository;
    @Mock
    private ChatConversationRepository conversationRepository;
    @Mock
    private ChatMessageRepository messageRepository;
    @Mock
    private HistoryRagClient historyRagClient;

    private ContextMaintenanceService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        ContextProperties properties = new ContextProperties();
        properties.getMaintenance().setSupersededRetentionDays(180);
        service = new ContextMaintenanceService(
                l2Repository, l1Repository, conversationRepository,
                messageRepository, historyRagClient, properties);
        now = Instant.parse("2026-07-22T04:00:00Z");
    }

    @Test
    void voidExpiredL2_andDeleteCorrespondingVectors() {
        UserContextStateEntity expired = l2("s1", "active", "u1", "default",
                now.minus(1, ChronoUnit.HOURS), "msg-expired");
        when(l2Repository.findByStatusAndExpiresAtBefore("active", now))
                .thenReturn(List.of(expired));
        when(historyRagClient.delete("u1", "default", "msg-expired")).thenReturn(Mono.empty());

        int voided = service.voidExpiredL2(now);

        assertThat(voided).isEqualTo(1);
        ArgumentCaptor<UserContextStateEntity> captor = ArgumentCaptor.forClass(UserContextStateEntity.class);
        verify(l2Repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("void");
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(now);
        verify(historyRagClient).delete("u1", "default", "msg-expired");
    }

    @Test
    void gcOrphanL1_whenConversationMissing() {
        ConversationContextL1Entity orphan = new ConversationContextL1Entity();
        orphan.setConvId("conv-gone");
        ConversationContextL1Entity keep = new ConversationContextL1Entity();
        keep.setConvId("conv-live");
        when(l1Repository.findAll()).thenReturn(List.of(orphan, keep));
        when(conversationRepository.existsById("conv-gone")).thenReturn(false);
        when(conversationRepository.existsById("conv-live")).thenReturn(true);

        int deleted = service.gcOrphanL1();

        assertThat(deleted).isEqualTo(1);
        verify(l1Repository).delete(orphan);
        verify(l1Repository, never()).delete(keep);
    }

    @Test
    void cleanupLongSuperseded_deletesStaleRows() {
        Instant cutoff = now.minus(180, ChronoUnit.DAYS);
        UserContextStateEntity stale = l2("old", "superseded", "u1", "default", null, null);
        stale.setUpdatedAt(cutoff.minus(1, ChronoUnit.DAYS));
        when(l2Repository.findByStatusAndUpdatedAtBefore(eq("superseded"), any()))
                .thenReturn(List.of(stale));

        int deleted = service.cleanupLongSuperseded(now);

        assertThat(deleted).isEqualTo(1);
        verify(l2Repository).delete(stale);
    }

    @Test
    void gcL3Vectors_deletesVoidSourcesAndMissingMessages() {
        UserContextStateEntity voided = l2("v1", "void", "u1", "default", null, "msg-void");
        UserContextStateEntity orphanSrc = l2("a1", "active", "u1", "default", null, "msg-missing");
        when(l2Repository.findByStatus("void")).thenReturn(List.of(voided));
        when(l2Repository.findByStatus("active")).thenReturn(List.of(orphanSrc));
        when(messageRepository.existsById("msg-missing")).thenReturn(false);
        when(historyRagClient.delete(anyString(), anyString(), anyString())).thenReturn(Mono.empty());

        int count = service.gcL3Vectors();

        assertThat(count).isEqualTo(2);
        verify(historyRagClient).delete("u1", "default", "msg-void");
        verify(historyRagClient).delete("u1", "default", "msg-missing");
    }

    @Test
    void runOnce_swallowsFailures() {
        when(l2Repository.findByStatusAndExpiresAtBefore(anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        service.runOnce();
    }

    private static UserContextStateEntity l2(
            String id, String status, String userId, String tenantId,
            Instant expiresAt, String sourceMsgId) {
        UserContextStateEntity e = new UserContextStateEntity();
        e.setId(id);
        e.setStatus(status);
        e.setUserId(userId);
        e.setTenantId(tenantId);
        e.setKind("fact");
        e.setStateKey("k");
        e.setStateValue("v");
        e.setConfidence(0.9);
        e.setExpiresAt(expiresAt);
        e.setSourceMsgId(sourceMsgId);
        e.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        e.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return e;
    }
}
