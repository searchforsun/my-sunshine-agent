package com.sunshine.orchestrator.catalog;

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

@Slf4j
@Service
@RefreshScope
public class AgentCatalogService {
    private final AgentCatalogClient catalogClient;
    private volatile Map<String, AgentCatalogIndexEntry> indexEntries = Map.of();
    private final Map<String, AgentCatalogEntry> detailCache = new ConcurrentHashMap<>();

    public AgentCatalogService(AgentCatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<String, AgentCatalogIndexEntry> merged = new LinkedHashMap<>();
        for (AgentCatalogIndexEntry entry : catalogClient.fetchCatalogIndex()) {
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
}
