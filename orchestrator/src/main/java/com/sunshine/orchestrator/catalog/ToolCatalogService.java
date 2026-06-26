package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.agent.ToolResultSummarizer;
import com.sunshine.orchestrator.client.ToolCatalogClient;
import com.sunshine.orchestrator.processing.StepLabels;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 缓存 tool-manager catalog，并合并本地 RagTool 元数据
 */
@Slf4j
@Service
@RefreshScope
public class ToolCatalogService {

    private static final ToolCatalogEntry LOCAL_RAG = new ToolCatalogEntry(
            "search_knowledge",
            "检索知识库",
            "搜索企业知识库获取相关文档。当用户询问专业知识、公司政策、技术规范、操作手册等问题时优先调用。",
            "local",
            "rag",
            "hit-count",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of(
                                    "type", "string",
                                    "description", "自然语言查询文本，将用于向量检索匹配相关文档片段"))),
            "read");

    private final ToolCatalogClient catalogClient;
    private volatile Map<String, ToolCatalogEntry> entries = Map.of();

    public ToolCatalogService(ToolCatalogClient catalogClient) {
        this.catalogClient = catalogClient;
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
        merged.put(LOCAL_RAG.id(), LOCAL_RAG);
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

    public boolean useHitCountSummary(String toolId) {
        return find(toolId)
                .map(ToolCatalogEntry::outputSummaryKind)
                .map("hit-count"::equals)
                .orElse(false);
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
        String kind = find(toolName).map(ToolCatalogEntry::outputSummaryKind).orElse(null);
        if (kind != null) {
            return ToolResultSummarizer.summarizeByKind(kind, text);
        }
        return ToolResultSummarizer.summarize(toolName, text);
    }
}
