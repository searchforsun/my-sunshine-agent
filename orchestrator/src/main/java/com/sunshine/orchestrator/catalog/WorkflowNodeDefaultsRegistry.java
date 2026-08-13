package com.sunshine.orchestrator.catalog;

import com.sunshine.common.tool.WorkflowNodeExecutionPolicy;
import com.sunshine.orchestrator.client.WorkflowManagerClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 缓存 workflow-manager 节点执行默认策略（Nacos sunshine-workflow-manager.yaml via HTTP） */
@Slf4j
@Component
public class WorkflowNodeDefaultsRegistry {

    /** 冷启动占位；首次 refresh 成功后以服务端为准，失败时保留上一份（禁止反复静默回 platformDefault） */
    private volatile WorkflowNodeExecutionPolicy policy = WorkflowNodeExecutionPolicy.platformDefault();
    private volatile boolean loadedFromServer;

    private final WorkflowManagerClient workflowManagerClient;

    public WorkflowNodeDefaultsRegistry(WorkflowManagerClient workflowManagerClient) {
        this.workflowManagerClient = workflowManagerClient;
    }

    @PostConstruct
    void init() {
        refresh();
    }

    public WorkflowNodeExecutionPolicy policy() {
        return policy;
    }

    public boolean loadedFromServer() {
        return loadedFromServer;
    }

    public void refresh() {
        try {
            WorkflowManagerClient.WorkflowNodeDefaultsDto dto = workflowManagerClient.fetchNodeDefaults();
            if (dto != null) {
                policy = toPolicy(dto);
                loadedFromServer = true;
            } else if (!loadedFromServer) {
                log.warn("[WorkflowNodeDefaultsRegistry] fetch returned null; keeping cold-start policy");
            }
        } catch (Exception e) {
            log.warn("[WorkflowNodeDefaultsRegistry] fetch failed, keeping previous policy: {}", e.getMessage());
        }
    }

    private static WorkflowNodeExecutionPolicy toPolicy(WorkflowManagerClient.WorkflowNodeDefaultsDto dto) {
        WorkflowManagerClient.WorkflowNodeRetryDefaultsDto base = dto.defaults();
        Map<String, WorkflowNodeExecutionPolicy.NodeTypeOverride> byType = new LinkedHashMap<>();
        if (dto.byType() != null) {
            dto.byType().forEach((type, resolved) -> byType.put(type,
                    new WorkflowNodeExecutionPolicy.NodeTypeOverride(
                            resolved.maxAttempts(),
                            resolved.onFailure())));
        }
        List<String> retryOn = dto.retryOnErrorClass() != null
                ? dto.retryOnErrorClass()
                : WorkflowNodeExecutionPolicy.platformDefault().defaults().retryOnErrorClass();
        return new WorkflowNodeExecutionPolicy(
                new WorkflowNodeExecutionPolicy.NodeDefaults(
                        base.maxAttempts(),
                        base.backoffMs(),
                        dto.backoffMultiplier() > 0 ? dto.backoffMultiplier() : 2.0,
                        base.onFailure(),
                        retryOn),
                byType,
                dto.criticalOnFailure() != null ? dto.criticalOnFailure() : "fail_fast");
    }
}
