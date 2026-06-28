package com.sunshine.orchestrator.conversation;

import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.conversation.repo.ChatMessageRepository;
import com.sunshine.orchestrator.plan.ExecutionPlanEntity;
import com.sunshine.orchestrator.plan.ExecutionPlanRepository;
import com.sunshine.orchestrator.plan.ExecutionPlanStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Optional;

/**
 * 读取会话时修复 orphan streaming：execution_plan 已终态但 chat_message 未 commitFinal 落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePersistenceReconciler {

    private final ExecutionPlanRepository executionPlanRepository;
    private final ChatMessageRepository messageRepo;

    @Value("${agent.generation.orphan-timeout-sec:60}")
    private int orphanTimeoutSec;

    @Transactional
    public void reconcileStreamingAssistant(ChatMessageEntity msg) {
        if (!"assistant".equals(msg.getRole())) {
            return;
        }
        if (!MessageStatus.STREAMING.equals(msg.getStatus())) {
            return;
        }
        Optional<ExecutionPlanEntity> planOpt = resolvePlan(msg);
        if (planOpt.isPresent()) {
            ExecutionPlanStatus planStatus = ExecutionPlanStatus.fromDb(planOpt.get().getStatus());
            if (isTerminalPlan(planStatus)) {
                finalizeFromTerminalPlan(msg, planOpt.get(), planStatus);
                return;
            }
            if (planStatus == ExecutionPlanStatus.PAUSED) {
                linkPlanIfMissing(msg, planOpt.get());
                msg.setStatus(MessageStatus.INTERRUPTED);
                msg.setUpdatedAt(Instant.now());
                messageRepo.save(msg);
                log.info("[MessageReconcile] plan paused → interrupted msg={} plan={}",
                        msg.getId(), planOpt.get().getId());
                return;
            }
        }
        Instant threshold = Instant.now().minusSeconds(orphanTimeoutSec);
        if (msg.getUpdatedAt().isBefore(threshold)) {
            msg.setStatus(MessageStatus.INTERRUPTED);
            msg.setUpdatedAt(Instant.now());
            messageRepo.save(msg);
            log.info("[MessageReconcile] orphan streaming → interrupted msg={}", msg.getId());
        }
    }

    private Optional<ExecutionPlanEntity> resolvePlan(ChatMessageEntity msg) {
        if (StringUtils.hasText(msg.getExecutionPlanId())) {
            return executionPlanRepository.findById(msg.getExecutionPlanId().strip());
        }
        return executionPlanRepository.findByMessageId(msg.getId());
    }

    private void finalizeFromTerminalPlan(
            ChatMessageEntity msg,
            ExecutionPlanEntity plan,
            ExecutionPlanStatus planStatus) {
        linkPlanIfMissing(msg, plan);
        String messageStatus = planStatus == ExecutionPlanStatus.FAILED
                || planStatus == ExecutionPlanStatus.REJECTED
                ? MessageStatus.FAILED
                : MessageStatus.COMPLETED;
        msg.setStatus(messageStatus);
        msg.setUpdatedAt(Instant.now());
        messageRepo.save(msg);
        log.info("[MessageReconcile] streaming → {} msg={} plan={} planStatus={}",
                messageStatus, msg.getId(), plan.getId(), planStatus);
    }

    private static void linkPlanIfMissing(ChatMessageEntity msg, ExecutionPlanEntity plan) {
        if (!StringUtils.hasText(msg.getExecutionPlanId())) {
            msg.setExecutionPlanId(plan.getId());
        }
    }

    private static boolean isTerminalPlan(ExecutionPlanStatus status) {
        return status == ExecutionPlanStatus.COMPLETED
                || status == ExecutionPlanStatus.COMPLETED_WITH_ERRORS
                || status == ExecutionPlanStatus.FAILED
                || status == ExecutionPlanStatus.REJECTED
                || status == ExecutionPlanStatus.DEGRADED_REACT;
    }
}
