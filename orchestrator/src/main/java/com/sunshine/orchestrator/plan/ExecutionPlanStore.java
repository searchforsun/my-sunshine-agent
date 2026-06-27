package com.sunshine.orchestrator.plan;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.WorkflowContextCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 动态 Plan 持久化与状态机 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionPlanStore {

    private final ExecutionPlanRepository repository;
    private final PlanJsonCodec codec;
    private final PlanJsonParser planJsonParser;
    private final AgentPromptProperties agentPromptProperties;
    private final ConversationService conversationService;

    @Transactional
    public String createDraft(ExecutionStreamContext ctx, PlanJson planJson) {
        Instant now = Instant.now();
        ExecutionPlanEntity entity = new ExecutionPlanEntity();
        String id = resolvePlanId(planJson);
        entity.setId(id);
        entity.setConversationId(requireText(ctx.conversationId(), "conversationId"));
        entity.setMessageId(requireText(ctx.assistantMsgId(), "messageId"));
        entity.setUserId(requireText(ctx.userId(), "userId"));
        entity.setTenantId(StringUtils.hasText(ctx.tenantId()) ? ctx.tenantId() : "default");
        entity.setStatus(ExecutionPlanStatus.DRAFT.dbValue());
        entity.setPlannerModel(agentPromptProperties.plannerOrDefault().getModel());
        entity.setPlannerReason(truncate(planJson.reason(), 512));
        entity.setPlanJson(codec.toJson(planJson));
        entity.setReplanCount(0);
        entity.setCreatedAt(now);
        repository.save(entity);
        log.info("[ExecutionPlanStore] draft id={} msg={}", id, ctx.assistantMsgId());
        return id;
    }

    @Transactional
    public void appendPlannerAttempt(String planId, PlannerAttempt attempt) {
        ExecutionPlanEntity entity = requireEntity(planId);
        List<PlannerAttempt> attempts = new ArrayList<>(codec.plannerAttemptsFromJson(entity.getPlannerAttempts()));
        attempts.add(attempt);
        entity.setPlannerAttempts(codec.plannerAttemptsToJson(attempts));
        if ("replan".equals(attempt.phase()) && attempt.attemptNo() > 1) {
            entity.setReplanCount(Math.max(entity.getReplanCount(), attempt.attemptNo() - 1));
        }
        repository.save(entity);
    }

    @Transactional
    public void updatePlannerOutput(String planId, PlanJson planJson) {
        ExecutionPlanEntity entity = requireEntity(planId);
        entity.setPlanJson(codec.toJson(planJson));
        if (StringUtils.hasText(planJson.reason())) {
            entity.setPlannerReason(truncate(planJson.reason(), 512));
        }
        repository.save(entity);
    }

    @Transactional
    public void saveApprovalRounds(String planId, List<PlanApprovalRound> rounds) {
        ExecutionPlanEntity entity = requireEntity(planId);
        entity.setApprovalRounds(codec.approvalRoundsToJson(rounds));
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<PlanApprovalRound> listApprovalRounds(String planId) {
        return codec.approvalRoundsFromJson(requireEntity(planId).getApprovalRounds());
    }

    @Transactional
    public void markAwaitingApproval(String planId, PlanJson planJson) {
        ExecutionPlanEntity entity = requireEntity(planId);
        entity.setPlanJson(codec.toJson(planJson));
        entity.setStatus(ExecutionPlanStatus.AWAITING_APPROVAL.dbValue());
        repository.save(entity);
    }

    @Transactional
    public void markValidated(String planId, PlanJson planJson) {
        ExecutionPlanEntity entity = requireEntity(planId);
        ExecutionPlanStatus current = ExecutionPlanStatus.fromDb(entity.getStatus());
        if (current != ExecutionPlanStatus.DRAFT && current != ExecutionPlanStatus.AWAITING_APPROVAL) {
            assertStatus(entity, ExecutionPlanStatus.DRAFT);
        }
        entity.setStatus(ExecutionPlanStatus.VALIDATED.dbValue());
        entity.setValidatedJson(codec.toJson(planJson));
        entity.setValidatedAt(Instant.now());
        repository.save(entity);
        conversationService.linkMessageExecutionPlan(entity.getMessageId(), planId);
        log.info("[ExecutionPlanStore] validated id={}", planId);
    }

    @Transactional
    public void markRejected(String planId, String reason) {
        ExecutionPlanEntity entity = requireEntity(planId);
        if (isTerminal(entity.getStatus())) {
            return;
        }
        entity.setStatus(ExecutionPlanStatus.REJECTED.dbValue());
        entity.setRejectReason(truncate(reason, 512));
        entity.setCompletedAt(Instant.now());
        repository.save(entity);
        log.info("[ExecutionPlanStore] rejected id={} reason={}", planId, reason);
    }

    @Transactional
    public void markRunning(String planId) {
        ExecutionPlanEntity entity = requireEntity(planId);
        assertStatus(entity, ExecutionPlanStatus.VALIDATED);
        entity.setStatus(ExecutionPlanStatus.RUNNING.dbValue());
        entity.setStartedAt(Instant.now());
        repository.save(entity);
        log.info("[ExecutionPlanStore] running id={}", planId);
    }

    @Transactional
    public void upsertNodeTrace(String planId, PlanNodeTrace trace) {
        ExecutionPlanEntity entity = requireEntity(planId);
        List<PlanNodeTrace> traces = new ArrayList<>(codec.traceFromJson(entity.getExecutionTrace()));
        int idx = -1;
        for (int i = 0; i < traces.size(); i++) {
            if (trace.nodeId().equals(traces.get(i).nodeId())) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) {
            traces.set(idx, trace);
        } else {
            traces.add(trace);
        }
        entity.setExecutionTrace(codec.traceToJson(traces));
        repository.save(entity);
    }

    @Transactional
    public void markCompleted(String planId) {
        ExecutionPlanEntity entity = requireEntity(planId);
        if (isTerminal(entity.getStatus())) {
            return;
        }
        entity.setStatus(ExecutionPlanStatus.COMPLETED.dbValue());
        entity.setCompletedAt(Instant.now());
        repository.save(entity);
        log.info("[ExecutionPlanStore] completed id={}", planId);
    }

    @Transactional
    public void markCompletedWithErrors(String planId) {
        ExecutionPlanEntity entity = requireEntity(planId);
        if (isTerminal(entity.getStatus())) {
            return;
        }
        entity.setStatus(ExecutionPlanStatus.COMPLETED_WITH_ERRORS.dbValue());
        entity.setCompletedAt(Instant.now());
        repository.save(entity);
        log.info("[ExecutionPlanStore] completed_with_errors id={}", planId);
    }

    @Transactional
    public void markDegradedReact(String planId, String reason) {
        ExecutionPlanEntity entity = requireEntity(planId);
        if (isTerminal(entity.getStatus())) {
            return;
        }
        entity.setStatus(ExecutionPlanStatus.DEGRADED_REACT.dbValue());
        entity.setRejectReason(truncate(reason, 512));
        entity.setCompletedAt(Instant.now());
        repository.save(entity);
        log.info("[ExecutionPlanStore] degraded_react id={} reason={}", planId, reason);
    }

    @Transactional
    public void markFailed(String planId, String reason) {
        ExecutionPlanEntity entity = requireEntity(planId);
        if (isTerminal(entity.getStatus())) {
            return;
        }
        entity.setStatus(ExecutionPlanStatus.FAILED.dbValue());
        entity.setRejectReason(truncate(reason, 512));
        entity.setCompletedAt(Instant.now());
        repository.save(entity);
        log.info("[ExecutionPlanStore] failed id={} reason={}", planId, reason);
    }

    @Transactional(readOnly = true)
    public ExecutionPlanEntity get(String planId) {
        return requireEntity(planId);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ExecutionPlanEntity> findByMessageId(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return java.util.Optional.empty();
        }
        return repository.findByMessageId(messageId.strip());
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ExecutionPlanEntity> findPausedForMessage(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return java.util.Optional.empty();
        }
        return repository.findByMessageId(messageId.strip())
                .filter(e -> ExecutionPlanStatus.PAUSED == ExecutionPlanStatus.fromDb(e.getStatus()));
    }

    /** 续跑：PAUSED、FAILED+checkpoint、或 cancel 竞态下 RUNNING/VALIDATED 但已有 checkpoint */
    @Transactional(readOnly = true)
    public java.util.Optional<ExecutionPlanEntity> findResumableForMessage(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return java.util.Optional.empty();
        }
        return repository.findByMessageId(messageId.strip())
                .filter(this::isResumablePlan);
    }

    private boolean isResumablePlan(ExecutionPlanEntity entity) {
        ExecutionPlanStatus status = ExecutionPlanStatus.fromDb(entity.getStatus());
        if (status == ExecutionPlanStatus.PAUSED) {
            return true;
        }
        if (status == ExecutionPlanStatus.AWAITING_APPROVAL
                && StringUtils.hasText(entity.getValidatedJson())) {
            return true;
        }
        if (status == ExecutionPlanStatus.FAILED
                && StringUtils.hasText(entity.getPauseCheckpoint())) {
            return true;
        }
        if (StringUtils.hasText(entity.getPauseCheckpoint())
                && (status == ExecutionPlanStatus.RUNNING
                || status == ExecutionPlanStatus.VALIDATED)) {
            return true;
        }
        return false;
    }

    @Transactional
    public void markPaused(String planId, WorkflowCheckpoint checkpoint) {
        ExecutionPlanEntity entity = requireEntity(planId);
        ExecutionPlanStatus current = ExecutionPlanStatus.fromDb(entity.getStatus());
        if (current != ExecutionPlanStatus.RUNNING
                && current != ExecutionPlanStatus.PAUSED
                && current != ExecutionPlanStatus.VALIDATED
                && current != ExecutionPlanStatus.DRAFT
                && current != ExecutionPlanStatus.AWAITING_APPROVAL) {
            return;
        }
        WorkflowCheckpoint toSave = checkpoint;
        if (!WorkflowContextCodec.hasNodes(checkpoint.wfCtxJson())
                && StringUtils.hasText(entity.getPauseCheckpoint())) {
            WorkflowCheckpoint prev = codec.checkpointFromJson(entity.getPauseCheckpoint());
            if (WorkflowContextCodec.hasNodes(prev.wfCtxJson())) {
                PausePhase phase = checkpoint.pausePhase() != null ? checkpoint.pausePhase() : prev.pausePhase();
                PendingInteraction pending = checkpoint.pendingInteraction() != null
                        ? checkpoint.pendingInteraction() : prev.pendingInteraction();
                toSave = new WorkflowCheckpoint(checkpoint.resumeNodeId(), prev.wfCtxJson(), phase, pending);
                log.info("[ExecutionPlanStore] paused 使用 DB wfCtx 快照 id={}", planId);
            }
        }
        entity.setStatus(ExecutionPlanStatus.PAUSED.dbValue());
        entity.setPauseCheckpoint(codec.checkpointToJson(toSave));
        repository.save(entity);
        log.info("[ExecutionPlanStore] paused id={} resumeNode={} phase={}",
                planId, toSave.resumeNodeId(), toSave.pausePhase());
    }

    /** 工作流停止时是否应落库检查点 */
    @Transactional(readOnly = true)
    public boolean isPausableForWorkflowStop(ExecutionPlanEntity entity) {
        ExecutionPlanStatus status = ExecutionPlanStatus.fromDb(entity.getStatus());
        return status == ExecutionPlanStatus.RUNNING
                || status == ExecutionPlanStatus.VALIDATED
                || status == ExecutionPlanStatus.PAUSED
                || status == ExecutionPlanStatus.DRAFT
                || status == ExecutionPlanStatus.AWAITING_APPROVAL;
    }

    /** PLANNING 阶段续跑起始节点：validated 已有则取 DAG 首业务节点 */
    @Transactional(readOnly = true)
    public String inferPlanningResumeNodeId(ExecutionPlanEntity entity) {
        if (!StringUtils.hasText(entity.getValidatedJson())) {
            return "";
        }
        try {
            PlanJson plan = PlanNormalizer.normalize(planJsonParser.parse(entity.getValidatedJson()));
            return PlanLinearizer.linearOrder(plan).stream()
                    .filter(id -> !"start".equals(id))
                    .findFirst()
                    .orElse("");
        } catch (Exception e) {
            log.warn("[ExecutionPlanStore] inferPlanningResumeNodeId failed: {}", e.getMessage());
            return "";
        }
    }

    /** 节点成功后刷新 wfCtx 快照，供暂停续跑（避免 cancel 时内存已清空） */
    @Transactional
    public void refreshCheckpointWfCtx(String planId, WorkflowContext wfCtx) {
        ExecutionPlanEntity entity = requireEntity(planId);
        ExecutionPlanStatus status = ExecutionPlanStatus.fromDb(entity.getStatus());
        if (status != ExecutionPlanStatus.RUNNING && status != ExecutionPlanStatus.PAUSED) {
            return;
        }
        String wfJson = WorkflowContextCodec.toJson(wfCtx);
        if (!WorkflowContextCodec.hasNodes(wfJson)) {
            return;
        }
        String resumeNodeId = "";
        if (StringUtils.hasText(entity.getPauseCheckpoint())) {
            try {
                resumeNodeId = codec.checkpointFromJson(entity.getPauseCheckpoint()).resumeNodeId();
            } catch (Exception ignored) {
                resumeNodeId = "";
            }
        }
        entity.setPauseCheckpoint(codec.checkpointToJson(
                new WorkflowCheckpoint(resumeNodeId != null ? resumeNodeId : "", wfJson)));
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<PlanNodeTrace> listNodeTraces(String planId) {
        return codec.traceFromJson(requireEntity(planId).getExecutionTrace());
    }

    @Transactional
    public void markResumed(String planId) {
        ExecutionPlanEntity entity = requireEntity(planId);
        ExecutionPlanStatus status = ExecutionPlanStatus.fromDb(entity.getStatus());
        if (status != ExecutionPlanStatus.PAUSED
                && status != ExecutionPlanStatus.FAILED
                && status != ExecutionPlanStatus.AWAITING_APPROVAL
                && status != ExecutionPlanStatus.RUNNING
                && status != ExecutionPlanStatus.VALIDATED) {
            assertStatus(entity, ExecutionPlanStatus.PAUSED);
        }
        entity.setStatus(ExecutionPlanStatus.RUNNING.dbValue());
        repository.save(entity);
        log.info("[ExecutionPlanStore] resumed id={}", planId);
    }

    public WorkflowCheckpoint loadCheckpoint(ExecutionPlanEntity entity) {
        return codec.checkpointFromJson(entity.getPauseCheckpoint());
    }

    private static String resolvePlanId(PlanJson planJson) {
        if (planJson != null && StringUtils.hasText(planJson.planId())) {
            return planJson.planId().strip();
        }
        return UUID.randomUUID().toString();
    }

    private ExecutionPlanEntity requireEntity(String planId) {
        return repository.findById(planId)
                .orElseThrow(() -> new BizException(OrchestratorErrorCode.EXECUTION_PLAN_NOT_FOUND));
    }

    private static void assertStatus(ExecutionPlanEntity entity, ExecutionPlanStatus expected) {
        ExecutionPlanStatus current = ExecutionPlanStatus.fromDb(entity.getStatus());
        if (current != expected) {
            throw new BizException(OrchestratorErrorCode.EXECUTION_PLAN_STATE_INVALID);
        }
    }

    private static boolean isTerminal(String status) {
        ExecutionPlanStatus s = ExecutionPlanStatus.fromDb(status);
        return s == ExecutionPlanStatus.COMPLETED
                || s == ExecutionPlanStatus.COMPLETED_WITH_ERRORS
                || s == ExecutionPlanStatus.FAILED
                || s == ExecutionPlanStatus.REJECTED
                || s == ExecutionPlanStatus.DEGRADED_REACT;
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(OrchestratorErrorCode.EXECUTION_PLAN_PERSIST_INCOMPLETE);
        }
        return value;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.length() <= maxLen ? trimmed : trimmed.substring(0, maxLen);
    }
}
