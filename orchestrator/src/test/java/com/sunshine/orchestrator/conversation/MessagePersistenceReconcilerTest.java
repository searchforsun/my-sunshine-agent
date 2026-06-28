package com.sunshine.orchestrator.conversation;

import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import com.sunshine.orchestrator.plan.ExecutionPlanEntity;
import com.sunshine.orchestrator.plan.ExecutionPlanRepository;
import com.sunshine.orchestrator.plan.ExecutionPlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagePersistenceReconcilerTest {

    @Mock
    private ExecutionPlanRepository executionPlanRepository;
    @Mock
    private ChatMessageRepository messageRepo;
    @InjectMocks
    private MessagePersistenceReconciler reconciler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reconciler, "orphanTimeoutSec", 60);
    }

    @Test
    @DisplayName("plan 已 completed 时 streaming assistant 归一为 completed")
    void reconcile_terminalPlan() {
        ChatMessageEntity msg = assistant("msg-1");
        msg.setExecutionPlanId("plan-1");
        ExecutionPlanEntity plan = new ExecutionPlanEntity();
        plan.setId("plan-1");
        plan.setStatus(ExecutionPlanStatus.COMPLETED.dbValue());
        when(executionPlanRepository.findById("plan-1")).thenReturn(Optional.of(plan));
        when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        reconciler.reconcileStreamingAssistant(msg);

        assertThat(msg.getStatus()).isEqualTo(MessageStatus.COMPLETED);
        verify(messageRepo).save(msg);
    }

    @Test
    @DisplayName("无 plan 且未超时时不改动 streaming")
    void reconcile_recentStreamingUntouched() {
        ChatMessageEntity msg = assistant("msg-2");
        msg.setUpdatedAt(Instant.now());
        when(executionPlanRepository.findByMessageId("msg-2")).thenReturn(Optional.empty());

        reconciler.reconcileStreamingAssistant(msg);

        assertThat(msg.getStatus()).isEqualTo(MessageStatus.STREAMING);
        verify(messageRepo, never()).save(any());
    }

    @Test
    @DisplayName("无 plan 且超时 orphan streaming → interrupted")
    void reconcile_orphanTimeout() {
        ChatMessageEntity msg = assistant("msg-3");
        msg.setUpdatedAt(Instant.now().minusSeconds(120));
        when(executionPlanRepository.findByMessageId("msg-3")).thenReturn(Optional.empty());
        when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        reconciler.reconcileStreamingAssistant(msg);

        assertThat(msg.getStatus()).isEqualTo(MessageStatus.INTERRUPTED);
        verify(messageRepo).save(msg);
    }

    private static ChatMessageEntity assistant(String id) {
        ChatMessageEntity msg = new ChatMessageEntity();
        msg.setId(id);
        msg.setRole("assistant");
        msg.setStatus(MessageStatus.STREAMING);
        msg.setUpdatedAt(Instant.now().minusSeconds(5));
        return msg;
    }
}
