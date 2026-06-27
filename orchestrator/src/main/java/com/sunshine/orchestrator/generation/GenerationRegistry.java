package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.execution.WorkflowPauseService;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.hitl.WorkflowNodeRecoveryService;
import com.sunshine.orchestrator.plan.PlanApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class GenerationRegistry {

    private final WorkflowPauseService workflowPauseService;
    private final ConcurrentHashMap<String, GenerationJob> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> messageLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> messageToGeneration = new ConcurrentHashMap<>();

    @Autowired(required = false)
    @Lazy
    private HitlConfirmationService hitlConfirmationService;

    @Autowired(required = false)
    @Lazy
    private PlanApprovalService planApprovalService;

    @Autowired(required = false)
    @Lazy
    private WorkflowNodeRecoveryService workflowNodeRecoveryService;

    public GenerationJob register(GenerationJob job) {
        running.put(job.getGenerationId(), job);
        messageToGeneration.put(job.getMessageId(), job.getGenerationId());
        return job;
    }

    public Optional<GenerationJob> get(String generationId) {
        return Optional.ofNullable(running.get(generationId));
    }

    public Optional<GenerationJob> findByMessageId(String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        String generationId = messageToGeneration.get(messageId);
        return generationId == null ? Optional.empty() : get(generationId);
    }

    public void remove(String generationId) {
        GenerationJob job = running.remove(generationId);
        if (job != null) {
            messageToGeneration.remove(job.getMessageId());
            unlockMessage(job.getMessageId());
        }
    }

    public void cancel(String generationId) {
        GenerationJob job = running.get(generationId);
        if (job != null) {
            releaseBlockingWaits(job.getMessageId());
            workflowPauseService.requestPause(job.getMessageId());
            job.cancel();
            remove(generationId);
        }
    }

    /** 停止所有进行中的 generation（测试 teardown / 优雅停机） */
    public void cancelAll() {
        for (String generationId : java.util.List.copyOf(running.keySet())) {
            cancel(generationId);
        }
    }

    public boolean tryLockMessage(String messageId, String generationId) {
        return messageLocks.putIfAbsent(messageId, generationId) == null
                || generationId.equals(messageLocks.get(messageId));
    }

    /** 续跑前：无活跃 job 时清除遗留 message 锁（HITL/Plan 阻塞导致 cancel 未完成） */
    public void clearStaleLockIfNoActiveJob(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        if (findByMessageId(messageId).isPresent()) {
            return;
        }
        messageLocks.remove(messageId);
    }

    public void unlockMessage(String messageId) {
        messageLocks.remove(messageId);
    }

    /** cancel 路径在 job 已移除时仍须解除 HITL/Plan/Recovery 阻塞 */
    public void releaseBlockingWaitsForMessage(String messageId) {
        releaseBlockingWaits(messageId);
    }

    private void releaseBlockingWaits(String messageId) {
        if (hitlConfirmationService != null) {
            hitlConfirmationService.cancelWaitersForMessage(messageId);
        }
        if (planApprovalService != null) {
            planApprovalService.cancelWaitersForMessage(messageId);
        }
        if (workflowNodeRecoveryService != null) {
            workflowNodeRecoveryService.cancelWaitersForMessage(messageId);
        }
    }
}
