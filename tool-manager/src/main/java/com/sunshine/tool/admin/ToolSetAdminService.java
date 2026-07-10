package com.sunshine.tool.admin;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.tool.ToolIds;
import com.sunshine.tool.admin.dto.ToolSetResponse;
import com.sunshine.tool.admin.dto.ToolSetUpdateRequest;
import com.sunshine.tool.entity.ToolSetEntity;
import com.sunshine.tool.entity.ToolSetMemberEntity;
import com.sunshine.tool.event.ToolCatalogChangePublisher;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.ToolSetMemberRepository;
import com.sunshine.tool.repo.ToolSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ToolSetAdminService {

    private static final ToolSetKind REACT_DEFAULT = new ToolSetKind(
            "global_react_default",
            "tenant_react_default",
            "global-react-default",
            "租户 ReAct 默认工具集",
            "tenant-%s-react-default");
    private static final ToolSetKind PLAN_WORKFLOW_CRITICAL = new ToolSetKind(
            "global_plan_workflow_critical",
            "tenant_plan_workflow_critical",
            "global-plan-workflow-critical",
            "租户 Plan/Workflow 关键工具集",
            "tenant-%s-plan-workflow-critical");

    private final ToolSetRepository toolSetRepository;
    private final ToolSetMemberRepository toolSetMemberRepository;
    @Autowired(required = false)
    private ToolCatalogChangePublisher catalogChangePublisher;

    public ToolSetResponse getReactDefault(String tenantId) {
        return getToolSet(REACT_DEFAULT, tenantId);
    }

    @Transactional
    public ToolSetResponse putReactDefault(String tenantId, ToolSetUpdateRequest request) {
        return putToolSet(REACT_DEFAULT, tenantId, request);
    }

    public ToolSetResponse getPlanWorkflowCritical(String tenantId) {
        return getToolSet(PLAN_WORKFLOW_CRITICAL, tenantId);
    }

    @Transactional
    public ToolSetResponse putPlanWorkflowCritical(String tenantId, ToolSetUpdateRequest request) {
        return putToolSet(PLAN_WORKFLOW_CRITICAL, tenantId, request);
    }

    private ToolSetResponse getToolSet(ToolSetKind kind, String tenantId) {
        ToolSetEntity set = resolveSet(kind, tenantId)
                .orElseThrow(() -> new BizException(ToolErrorCode.TOOL_SET_NOT_FOUND));
        return new ToolSetResponse(listToolIds(set.getId()));
    }

    private ToolSetResponse putToolSet(ToolSetKind kind, String tenantId, ToolSetUpdateRequest request) {
        List<String> toolIds = request != null && request.toolIds() != null ? request.toolIds() : List.of();
        ToolSetEntity set = resolveOrCreateSet(kind, tenantId);
        toolSetMemberRepository.deleteBySetId(set.getId());
        int order = 0;
        for (String toolId : toolIds) {
            if (!StringUtils.hasText(toolId)) {
                continue;
            }
            String normalized = toolId.strip();
            if (!ToolIds.isValid(normalized)) {
                throw new BizException(ToolErrorCode.TOOL_ID_INVALID);
            }
            ToolSetMemberEntity member = new ToolSetMemberEntity();
            member.setSetId(set.getId());
            member.setToolId(normalized);
            member.setSortOrder(order++);
            toolSetMemberRepository.save(member);
        }
        set.setUpdatedAt(Instant.now());
        toolSetRepository.save(set);
        publish(tenantId);
        return new ToolSetResponse(toolIds);
    }

    private Optional<ToolSetEntity> resolveSet(ToolSetKind kind, String tenantId) {
        if (StringUtils.hasText(tenantId) && !"default".equalsIgnoreCase(tenantId.strip())) {
            Optional<ToolSetEntity> tenantSet = toolSetRepository.findBySetTypeAndTenantId(
                    kind.tenantType(), tenantId.strip());
            if (tenantSet.isPresent()) {
                return tenantSet;
            }
        }
        return toolSetRepository.findBySetTypeAndTenantId(kind.globalType(), null);
    }

    private ToolSetEntity resolveOrCreateSet(ToolSetKind kind, String tenantId) {
        if (StringUtils.hasText(tenantId) && !"default".equalsIgnoreCase(tenantId.strip())) {
            return toolSetRepository.findBySetTypeAndTenantId(kind.tenantType(), tenantId.strip())
                    .orElseGet(() -> createTenantSet(kind, tenantId.strip()));
        }
        return toolSetRepository.findBySetTypeAndTenantId(kind.globalType(), null)
                .orElseThrow(() -> new BizException(ToolErrorCode.TOOL_SET_NOT_FOUND));
    }

    private ToolSetEntity createTenantSet(ToolSetKind kind, String tenantId) {
        ToolSetEntity entity = new ToolSetEntity();
        entity.setId(kind.tenantSetIdPattern().formatted(tenantId));
        entity.setSetType(kind.tenantType());
        entity.setTenantId(tenantId);
        entity.setDisplayName(kind.tenantDisplayName());
        entity.setUpdatedAt(Instant.now());
        return toolSetRepository.save(entity);
    }

    private List<String> listToolIds(String setId) {
        return toolSetMemberRepository.findBySetIdOrderBySortOrderAsc(setId).stream()
                .map(ToolSetMemberEntity::getToolId)
                .toList();
    }

    private void publish(String tenantId) {
        if (catalogChangePublisher != null) {
            catalogChangePublisher.publish(tenantId);
        }
    }

    private record ToolSetKind(
            String globalType,
            String tenantType,
            String globalSetId,
            String tenantDisplayName,
            String tenantSetIdPattern) {
    }
}
