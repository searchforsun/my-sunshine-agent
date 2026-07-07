package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.ToolCatalogClient;
import com.sunshine.orchestrator.client.ToolManagerClient;
import com.sunshine.orchestrator.client.ToolSummarizeOutputResponse;
import com.sunshine.orchestrator.processing.StepLabels;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 缓存 tool-manager catalog；工具输出摘要委托 tool-manager API */
@Slf4j
@Service
@RefreshScope
public class ToolCatalogService {

    private final ToolCatalogClient catalogClient;
    private final ToolManagerClient toolManagerClient;
    private volatile Map<String, ToolCatalogEntry> entries = Map.of();

    public ToolCatalogService(ToolCatalogClient catalogClient, ToolManagerClient toolManagerClient) {
        this.catalogClient = catalogClient;
        this.toolManagerClient = toolManagerClient;
    }

    @PostConstruct
    void init() {
        refresh();
        StepLabels.bind(this);
    }

    public synchronized void refresh() {
        Map<String, ToolCatalogEntry> merged = new LinkedHashMap<>();
        for (ToolCatalogEntry entry : catalogClient.fetchCatalog()) {
            merged.put(entry.id(), entry);
        }
        this.entries = Map.copyOf(merged);
        log.info("[ToolCatalogService] catalog loaded: {}", String.join(", ", entries.keySet()));
    }

    public Optional<ToolCatalogEntry> find(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(toolId));
    }

    public String displayName(String toolId) {
        return find(toolId).map(ToolCatalogEntry::displayName).orElse(toolId);
    }

    public String timelinePhase(String toolId) {
        return find(toolId).map(ToolCatalogEntry::timelinePhase).orElse("tool");
    }

    public boolean isRagTool(String toolId) {
        return "rag".equals(timelinePhase(toolId));
    }

    public List<ToolCatalogEntry> allEntries() {
        return List.copyOf(entries.values());
    }

    public boolean isRemoteTool(String toolId) {
        return find(toolId).map(e -> "remote".equals(e.kind())).orElse(false);
    }

    public boolean isWriteTool(String toolId) {
        return find(toolId).map(ToolCatalogEntry::isWrite).orElse(false);
    }

    /** rag 工具用固定 stepId，其余为 tool-{name} */
    public String timelineStepId(String toolName) {
        return isRagTool(toolName) ? "rag" : "tool-" + toolName;
    }

    public String summarizeOutput(String toolName, String text) {
        return summarizeOutputDetail(toolName, text).summary();
    }

    public ToolSummarizeOutputResponse summarizeOutputDetail(String toolName, String text) {
        ToolSummarizeOutputResponse response = toolManagerClient.summarizeOutputMono(toolName, text).block();
        if (response == null) {
            return new ToolSummarizeOutputResponse("", true, true);
        }
        return response;
    }

    public ToolSummarizeOutputResponse summarizeByKind(String outputSummaryKind, String text) {
        ToolSummarizeOutputResponse response = toolManagerClient.summarizeByKindMono(outputSummaryKind, text).block();
        if (response == null) {
            return new ToolSummarizeOutputResponse("", true, true);
        }
        return response;
    }
}
