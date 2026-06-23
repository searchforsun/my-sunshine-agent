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
        entity.setCreatedAt(now);
        repository.save(entity);
        conversationService.linkMessageExecutionPlan(ctx.assistantMsgId(), id);
        log.info("[ExecutionPlanStore] draft id={} msg={}", id, ctx.assistantMsgId());
        return id;
    }

    @Transactional
    public void markValidated(String planId, PlanJson planJson) {
        ExecutionPlanEntity entity = requireEntity(planId);
        assertStatus(entity, ExecutionPlanStatus.DRAFT);
        entity.setStatus(ExecutionPlanStatus.VALIDATED.dbValue());
        entity.setValidatedJson(codec.toJson(planJson));
        entity.setValidatedAt(Instant.now());
        repository.save(entity);
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
    public void appendNodeTrace(String planId, PlanNodeTrace trace) {
        ExecutionPlanEntity entity = requireEntity(planId);
        List<PlanNodeTrace> traces = new ArrayList<>(codec.traceFromJson(entity.getExecutionTrace()));
        traces.add(trace);
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
                || s == ExecutionPlanStatus.FAILED
                || s == ExecutionPlanStatus.REJECTED;
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
