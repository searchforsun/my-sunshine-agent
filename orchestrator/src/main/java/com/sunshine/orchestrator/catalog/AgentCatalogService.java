package com.sunshine.orchestrator.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.AgentCatalogClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RefreshScope
public class AgentCatalogService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentCatalogClient catalogClient;
    private final ToolCatalogService toolCatalogService;
    private volatile Map<String, AgentCatalogIndexEntry> indexEntries = Map.of();
    private final Map<String, AgentCatalogEntry> detailCache = new ConcurrentHashMap<>();

    public AgentCatalogService(AgentCatalogClient catalogClient, ToolCatalogService toolCatalogService) {
        this.catalogClient = catalogClient;
        this.toolCatalogService = toolCatalogService;
    }

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<String, AgentCatalogIndexEntry> merged = new LinkedHashMap<>();
        for (AgentCatalogIndexEntry entry : catalogClient.fetchCatalogIndex(null)) {
            if (entry.id() != null) {
                merged.put(entry.id(), entry);
            }
        }
        this.indexEntries = Map.copyOf(merged);
        this.detailCache.clear();
        log.info("[AgentCatalogService] index loaded: {}", String.join(", ", indexEntries.keySet()));
    }

    public List<AgentCatalogIndexEntry> indexEntries() {
        return List.copyOf(indexEntries.values());
    }

    /** L3 轨 A 意图收集 — Agent 目录（按会话 kind 过滤：保留 all + 同 kind） */
    public String renderForClassifier(String sessionKind) {
        if (indexEntries().isEmpty()) {
            return "(无 Agent 目录)";
        }
        return indexEntries().stream()
                .filter(AgentCatalogIndexEntry::enabled)
                .filter(e -> !StringUtils.hasText(sessionKind) || ResourceKindFilter.matches(e.kind(), sessionKind))
                .map(e -> "- **" + e.id() + "**: " + e.displayName()
                        + (StringUtils.hasText(e.description()) ? " — " + e.description() : ""))
                .collect(Collectors.joining("\n"));
    }

    public String renderIntoClassifier(String classifierPrompt, String sessionKind) {
        if (!StringUtils.hasText(classifierPrompt)) {
            return classifierPrompt;
        }
        return classifierPrompt.replace("{{agent-catalog}}", renderForClassifier(sessionKind));
    }

    public boolean isKnownAgent(String agentId) {
        return findIndex(agentId).isPresent();
    }

    public Optional<AgentCatalogIndexEntry> findIndex(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return Optional.empty();
        }
        if (indexEntries.isEmpty()) {
            refresh();
        }
        return Optional.ofNullable(indexEntries.get(agentId.strip()));
    }

    public Optional<AgentCatalogEntry> find(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return Optional.empty();
        }
        String id = agentId.strip();
        AgentCatalogEntry cached = detailCache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<AgentCatalogEntry> loaded = catalogClient.fetchAgentDetail(id);
        loaded.ifPresent(entry -> detailCache.put(id, entry));
        return loaded;
    }

    /**
     * spawn-hint {agents} 渲染：每个预定义智能体行附带其声明工具清单（可读名）。
     * 仅用内存索引（ReactExecutor 在 reactor-http 线程执行，禁止远程 find）；
     * 工具名经 ToolCatalogService 内存目录转可读名，缺失时回退 Catalog ID。
     */
    public String renderForSpawnHint(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return "";
        }
        return agentIds.stream()
                .map(String::strip)
                .filter(StringUtils::hasText)
                .map(this::renderAgentLine)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
    }

    private String renderAgentLine(String agentId) {
        Optional<AgentCatalogIndexEntry> opt = findIndex(agentId);
        if (opt.isEmpty()) {
            return "";
        }
        AgentCatalogIndexEntry entry = opt.get();
        StringBuilder line = new StringBuilder("- ").append(entry.id());
        if (StringUtils.hasText(entry.displayName())) {
            line.append(" (").append(entry.displayName()).append(")");
        }
        if (StringUtils.hasText(entry.description())) {
            line.append(": ").append(entry.description());
        }
        List<String> toolNames = parseToolIds(entry.toolsJson());
        if (!toolNames.isEmpty()) {
            line.append("\n  - 已装配工具：").append(String.join("、", toolNames));
        }
        return line.toString();
    }

    private List<String> parseToolIds(String toolsJson) {
        if (!StringUtils.hasText(toolsJson) || "[]".equals(toolsJson)) {
            return List.of();
        }
        try {
            List<String> ids = MAPPER.readValue(toolsJson, new TypeReference<List<String>>() {});
            return ids == null ? List.of()
                    : ids.stream().filter(StringUtils::hasText)
                        .map(toolCatalogService::displayName)
                        .toList();
        } catch (Exception e) {
            log.warn("[AgentCatalogService] 解析 {} 工具清单失败: {}", toolsJson, e.getMessage());
            return List.of();
        }
    }
}
