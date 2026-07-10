package com.sunshine.tool.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.tool.PlanWorkflowExecutionPolicy;
import com.sunshine.tool.entity.ExecutionModePolicyEntity;
import com.sunshine.tool.event.ToolCatalogChangePublisher;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.repo.ExecutionModePolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ExecutionModePolicyAdminService {

    private static final String PLAN_WORKFLOW_MODE = "plan_workflow";
    private static final String GLOBAL_PLAN_POLICY_ID = "global-plan-workflow-policy";

    private final ExecutionModePolicyRepository policyRepository;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private ToolCatalogChangePublisher catalogChangePublisher;

    public PlanWorkflowExecutionPolicy getPlanWorkflow(String tenantId) {
        return resolvePolicy(tenantId)
                .map(this::toPolicy)
                .orElseGet(PlanWorkflowExecutionPolicy::platformDefault);
    }

    @Transactional
    public PlanWorkflowExecutionPolicy putPlanWorkflow(String tenantId, PlanWorkflowExecutionPolicy request) {
        PlanWorkflowExecutionPolicy normalized = request != null ? request : PlanWorkflowExecutionPolicy.platformDefault();
        ExecutionModePolicyEntity entity = resolveOrCreate(tenantId);
        entity.setPolicyJson(objectMapper.convertValue(normalized, Map.class));
        entity.setUpdatedAt(Instant.now());
        policyRepository.save(entity);
        publish(tenantId);
        return normalized;
    }

    private Optional<ExecutionModePolicyEntity> resolvePolicy(String tenantId) {
        if (StringUtils.hasText(tenantId) && !"default".equalsIgnoreCase(tenantId.strip())) {
            Optional<ExecutionModePolicyEntity> tenantPolicy = policyRepository.findByModeKeyAndTenantId(
                    PLAN_WORKFLOW_MODE, tenantId.strip());
            if (tenantPolicy.isPresent()) {
                return tenantPolicy;
            }
        }
        return policyRepository.findByModeKeyAndTenantId(PLAN_WORKFLOW_MODE, null);
    }

    private ExecutionModePolicyEntity resolveOrCreate(String tenantId) {
        if (StringUtils.hasText(tenantId) && !"default".equalsIgnoreCase(tenantId.strip())) {
            return policyRepository.findByModeKeyAndTenantId(PLAN_WORKFLOW_MODE, tenantId.strip())
                    .orElseGet(() -> createTenantPolicy(tenantId.strip()));
        }
        return policyRepository.findByModeKeyAndTenantId(PLAN_WORKFLOW_MODE, null)
                .orElseThrow(() -> new BizException(ToolErrorCode.EXECUTION_MODE_POLICY_NOT_FOUND));
    }

    private ExecutionModePolicyEntity createTenantPolicy(String tenantId) {
        ExecutionModePolicyEntity entity = new ExecutionModePolicyEntity();
        entity.setId("tenant-" + tenantId + "-plan-workflow-policy");
        entity.setModeKey(PLAN_WORKFLOW_MODE);
        entity.setTenantId(tenantId);
        entity.setPolicyJson(objectMapper.convertValue(PlanWorkflowExecutionPolicy.platformDefault(), Map.class));
        entity.setUpdatedAt(Instant.now());
        return policyRepository.save(entity);
    }

    private PlanWorkflowExecutionPolicy toPolicy(ExecutionModePolicyEntity entity) {
        if (entity.getPolicyJson() == null || entity.getPolicyJson().isEmpty()) {
            return PlanWorkflowExecutionPolicy.platformDefault();
        }
        return objectMapper.convertValue(entity.getPolicyJson(), PlanWorkflowExecutionPolicy.class);
    }

    private void publish(String tenantId) {
        if (catalogChangePublisher != null) {
            catalogChangePublisher.publish(tenantId);
        }
    }
}
