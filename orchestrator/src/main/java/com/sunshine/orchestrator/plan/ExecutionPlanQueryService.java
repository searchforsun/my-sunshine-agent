package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.conversation.ConversationNotFoundException;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.plan.dto.ExecutionPlanDetailDto;
import com.sunshine.orchestrator.plan.dto.ExecutionPlanSummaryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Plan 回放查询（鉴权：userId + tenantId） */
@Service
@RequiredArgsConstructor
public class ExecutionPlanQueryService {

    private final ExecutionPlanRepository repository;
    private final ConversationService conversationService;
    private final PlanJsonCodec codec;

    @Transactional(readOnly = true)
    public ExecutionPlanDetailDto getDetail(String planId, String userId, String tenantId) {
        ExecutionPlanEntity entity = requireOwned(planId, userId, tenantId);
        return ExecutionPlanDetailDto.from(entity, codec);
    }

    @Transactional(readOnly = true)
    public List<ExecutionPlanSummaryDto> listByConversation(
            String conversationId, String userId, String tenantId) {
        conversationService.getOwned(conversationId, userId, tenantId);
        return repository.findByConversationIdOrderByCreatedAtDesc(conversationId).stream()
                .map(ExecutionPlanSummaryDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PlanNodeTrace> listNodeTraces(String planId, String userId, String tenantId) {
        ExecutionPlanEntity entity = requireOwned(planId, userId, tenantId);
        return codec.traceFromJson(entity.getExecutionTrace());
    }

    private ExecutionPlanEntity requireOwned(String planId, String userId, String tenantId) {
        ExecutionPlanEntity entity = repository.findById(planId)
                .orElseThrow(() -> new ConversationNotFoundException("执行计划不存在"));
        if (!Objects.equals(entity.getUserId(), userId)
                || !Objects.equals(entity.getTenantId(), tenantId)) {
            throw new ConversationNotFoundException("执行计划不存在");
        }
        return entity;
    }
}
