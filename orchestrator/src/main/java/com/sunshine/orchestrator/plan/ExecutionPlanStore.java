package com.sunshine.orchestrator.plan;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
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
    public void markValidated(String planId, PlanJson planJson) {
        ExecutionPlanEntity entity = requireEntity(planId);
        assertStatus(entity, ExecutionPlanStatus.DRAFT);
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

    private static String resolvePlanId(PlanJson planJson) {
        if (planJson != null && StringUtils.hasText(planJson.planId())) {
            return planJson.planId().strip();
        }
        return UUID.randomUUID().toString();
    }

    private ExecutionPlanEntity requireEntity(String planId) {
        return repository.findById(planId)
                .orElseThrow(() -> new BizException("执行计划不存在: " + planId));
    }

    private static void assertStatus(ExecutionPlanEntity entity, ExecutionPlanStatus expected) {
        ExecutionPlanStatus current = ExecutionPlanStatus.fromDb(entity.getStatus());
        if (current != expected) {
            throw new BizException("Plan 状态非法: " + current + "，期望 " + expected);
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
            throw new BizException("Plan 持久化缺少 " + field);
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
