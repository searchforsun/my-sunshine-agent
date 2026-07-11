package com.sunshine.orchestrator.catalog;

import com.sunshine.common.tool.ToolCatalogEntry;
import com.sunshine.orchestrator.agent.RagTool;
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
import java.util.Set;
import java.util.stream.Collectors;

/** 缓存 tool-manager catalog；工具输出摘要委托 tool-manager API */
@Slf4j
@Service
@RefreshScope
public class ToolCatalogService {

    private static final String DEFAULT_TENANT = "default";

    private final ToolManagerClient toolManagerClient;
    private volatile Map<String, ToolCatalogEntry> entries = Map.of();
    private volatile Set<String> defaultEnabledIds = Set.of();

    public ToolCatalogService(ToolManagerClient toolManagerClient) {
        this.toolManagerClient = toolManagerClient;
    }

    @PostConstruct
    void init() {
        refresh();
        StepLabels.bind(this);
    }

    public synchronized void refresh() {
        Map<String, ToolCatalogEntry> merged = new LinkedHashMap<>();
        for (ToolCatalogEntry entry : toolManagerClient.fetchCatalog(DEFAULT_TENANT, false)) {
            merged.put(entry.id(), entry);
        }
        this.entries = Map.copyOf(merged);
        this.defaultEnabledIds = toolManagerClient.fetchCatalog(DEFAULT_TENANT, true).stream()
                .map(ToolCatalogEntry::id)
                .collect(Collectors.toUnmodifiableSet());
        log.info("[ToolCatalogService] catalog loaded: {} (enabled={})",
                String.join(", ", entries.keySet()), String.join(", ", defaultEnabledIds));
    }

    /** 租户可见且启用的工具 id 池（ToolSet 白名单求交用） */
    public Set<String> enabledIds(String tenantId) {
        String effectiveTenant = tenantId == null || tenantId.isBlank() ? DEFAULT_TENANT : tenantId.strip();
        if (DEFAULT_TENANT.equals(effectiveTenant)) {
            return defaultEnabledIds;
        }
        return toolManagerClient.fetchCatalog(effectiveTenant, true).stream()
                .map(ToolCatalogEntry::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Optional<ToolCatalogEntry> find(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(toolId));
    }

    public String displayName(String toolId) {
        if (RagTool.NAME.equals(toolId)) {
            return "检索知识库";
        }
        return find(toolId).map(ToolCatalogEntry::displayName).orElse(toolId);
    }

    public String timelinePhase(String toolId) {
        return isRagTool(toolId) ? "rag" : "tool";
    }

    public boolean isRagTool(String toolId) {
        return RagTool.NAME.equals(toolId);
    }

    public List<ToolCatalogEntry> allEntries() {
        return List.copyOf(entries.values());
    }

    public boolean isRemoteTool(String toolId) {
        return find(toolId).map(e -> "remote".equals(e.kind()) || "mcp".equals(e.kind())).orElse(false);
    }

    public boolean requiresConfirmation(String toolId) {
        return find(toolId).map(ToolCatalogEntry::requireConfirmation).orElse(false);
    }

    /** rag 工具用固定 stepId，其余为 tool-{name} */
    public String timelineStepId(String toolName) {
        return isRagTool(toolName) ? "rag" : "tool-" + toolName;
    }

    public String summarizeOutput(String toolName, String text) {
        return summarizeOutputDetail(toolName, text).summary();
    }

    /**
     * 时间线用户可见一步摘要：仅当 catalog 配置了 timelineSummaryTemplate 且解析非空时返回；
     * 未配置则 null，由 Nacos agent.timeline.steps.tool 模板承接 after/detail。
     */
    public String timelineSummary(String toolName, String text) {
        ToolSummarizeOutputResponse response = summarizeOutputDetail(toolName, text);
        if (response.empty()) {
            return null;
        }
        String summary = response.summary();
        return summary != null && !summary.isBlank() ? summary : null;
    }

    public ToolSummarizeOutputResponse summarizeOutputDetail(String toolName, String text) {
        ToolSummarizeOutputResponse response = toolManagerClient.summarizeOutputMono(toolName, text).block();
        if (response == null) {
            return new ToolSummarizeOutputResponse("", true, true);
        }
        return response;
    }
}
