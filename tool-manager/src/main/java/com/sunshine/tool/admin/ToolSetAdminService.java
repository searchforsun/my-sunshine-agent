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

    private final ToolSetRepository toolSetRepository;
    private final ToolSetMemberRepository toolSetMemberRepository;
    @Autowired(required = false)
    private ToolCatalogChangePublisher catalogChangePublisher;

    public ToolSetResponse getReactDefault(String tenantId) {
        return getToolSet(ToolSetKind.REACT_DEFAULT, tenantId);
    }

    @Transactional
    public ToolSetResponse putReactDefault(String tenantId, ToolSetUpdateRequest request) {
        return putToolSet(ToolSetKind.REACT_DEFAULT, tenantId, request);
    }

    public ToolSetResponse getPlanWorkflow(String tenantId) {
        return getToolSet(ToolSetKind.PLAN_WORKFLOW, tenantId);
    }

    @Transactional
    public ToolSetResponse putPlanWorkflow(String tenantId, ToolSetUpdateRequest request) {
        return putToolSet(ToolSetKind.PLAN_WORKFLOW, tenantId, request);
    }

    private ToolSetResponse getToolSet(ToolSetKind kind, String tenantId) {
        return new ToolSetResponse(findSet(kind, tenantId)
                .map(set -> listToolIds(set.getId()))
                .orElse(List.of()));
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
            member.setCritical(false);
            toolSetMemberRepository.save(member);
        }
        set.setUpdatedAt(Instant.now());
        toolSetRepository.save(set);
        publish(tenantId);
        return new ToolSetResponse(toolIds);
    }

    private Optional<ToolSetEntity> findSet(ToolSetKind kind, String tenantId) {
        if (isTenantScoped(tenantId)) {
            return toolSetRepository.findBySetTypeAndTenantId(kind.tenantType(), tenantId.strip());
        }
        return toolSetRepository.findBySetTypeAndTenantId(kind.globalType(), null);
    }

    private static boolean isTenantScoped(String tenantId) {
        return StringUtils.hasText(tenantId) && !"default".equalsIgnoreCase(tenantId.strip());
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
}
