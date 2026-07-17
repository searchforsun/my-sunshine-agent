package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.WorkflowManagerClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 缓存 workflow-manager catalog */
@Slf4j
@Service
public class WorkflowCatalogRegistry {

    private final WorkflowManagerClient workflowManagerClient;
    private volatile Map<String, WorkflowManagerClient.WorkflowCatalogEntryDto> entries = Map.of();

    public WorkflowCatalogRegistry(WorkflowManagerClient workflowManagerClient) {
        this.workflowManagerClient = workflowManagerClient;
    }

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<String, WorkflowManagerClient.WorkflowCatalogEntryDto> merged = new LinkedHashMap<>();
        for (WorkflowManagerClient.WorkflowCatalogEntryDto entry : workflowManagerClient.fetchCatalog()) {
            if (entry.id() != null) {
                merged.put(entry.id(), entry);
            }
        }
        this.entries = Map.copyOf(merged);
        log.info("[WorkflowCatalogRegistry] loaded: {}", String.join(", ", entries.keySet()));
    }

    public List<WorkflowManagerClient.WorkflowCatalogEntryDto> entries() {
        return List.copyOf(entries.values());
    }

    public WorkflowManagerClient.WorkflowCatalogEntryDto find(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return null;
        }
        return entries.get(workflowId.strip());
    }
}
