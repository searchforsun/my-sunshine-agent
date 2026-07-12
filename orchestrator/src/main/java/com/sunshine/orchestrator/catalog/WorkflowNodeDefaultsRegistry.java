package com.sunshine.orchestrator.catalog;

import com.sunshine.common.tool.PlanWorkflowExecutionPolicy;
import com.sunshine.orchestrator.client.WorkflowManagerClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 缓存 workflow-manager 节点执行默认策略（Nacos sunshine-workflow-manager.yaml） */
@Slf4j
@Component
public class WorkflowNodeDefaultsRegistry {

    private volatile PlanWorkflowExecutionPolicy policy = PlanWorkflowExecutionPolicy.platformDefault();

    private final WorkflowManagerClient workflowManagerClient;

    public WorkflowNodeDefaultsRegistry(WorkflowManagerClient workflowManagerClient) {
        this.workflowManagerClient = workflowManagerClient;
    }

    @PostConstruct
    void init() {
        refresh();
    }

    public PlanWorkflowExecutionPolicy policy() {
        return policy;
    }

    public void refresh() {
        try {
            WorkflowManagerClient.WorkflowNodeDefaultsDto dto = workflowManagerClient.fetchNodeDefaults();
            if (dto != null) {
                policy = toPolicy(dto);
            }
        } catch (Exception e) {
            log.warn("[WorkflowNodeDefaultsRegistry] fetch failed, using code fallback: {}", e.getMessage());
        }
    }

    private static PlanWorkflowExecutionPolicy toPolicy(WorkflowManagerClient.WorkflowNodeDefaultsDto dto) {
        WorkflowManagerClient.WorkflowNodeRetryDefaultsDto base = dto.defaults();
        Map<String, PlanWorkflowExecutionPolicy.NodeTypeOverride> byType = new LinkedHashMap<>();
        if (dto.byType() != null) {
            dto.byType().forEach((type, resolved) -> byType.put(type,
                    new PlanWorkflowExecutionPolicy.NodeTypeOverride(
                            resolved.maxAttempts(),
                            resolved.onFailure())));
        }
        List<String> retryOn = dto.retryOnErrorClass() != null
                ? dto.retryOnErrorClass()
                : PlanWorkflowExecutionPolicy.platformDefault().defaults().retryOnErrorClass();
        return new PlanWorkflowExecutionPolicy(
                new PlanWorkflowExecutionPolicy.NodeDefaults(
                        base.maxAttempts(),
                        base.backoffMs(),
                        dto.backoffMultiplier() > 0 ? dto.backoffMultiplier() : 2.0,
                        base.onFailure(),
                        retryOn),
                byType,
                dto.criticalOnFailure() != null ? dto.criticalOnFailure() : "fail_fast");
    }
}
