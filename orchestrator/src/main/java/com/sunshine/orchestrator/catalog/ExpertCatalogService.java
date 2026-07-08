package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.ExpertCatalogClient;
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
public class ExpertCatalogService {
    private final ExpertCatalogClient catalogClient;
    private volatile Map<String, ExpertCatalogIndexEntry> indexEntries = Map.of();
    private final Map<String, ExpertCatalogEntry> detailCache = new ConcurrentHashMap<>();

    public ExpertCatalogService(ExpertCatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<String, ExpertCatalogIndexEntry> merged = new LinkedHashMap<>();
        for (ExpertCatalogIndexEntry entry : catalogClient.fetchCatalogIndex()) {
            if (entry.id() != null) {
                merged.put(entry.id(), entry);
            }
        }
        this.indexEntries = Map.copyOf(merged);
        this.detailCache.clear();
        log.info("[ExpertCatalogService] index loaded: {}", String.join(", ", indexEntries.keySet()));
    }

    public List<ExpertCatalogIndexEntry> indexEntries() {
        return List.copyOf(indexEntries.values());
    }

    public boolean isKnownExpert(String expertId) {
        return findIndex(expertId).isPresent();
    }

    public Optional<ExpertCatalogIndexEntry> findIndex(String expertId) {
        if (!StringUtils.hasText(expertId)) {
            return Optional.empty();
        }
        if (indexEntries.isEmpty()) {
            refresh();
        }
        return Optional.ofNullable(indexEntries.get(expertId.strip()));
    }

    public Optional<ExpertCatalogEntry> find(String expertId) {
        if (!StringUtils.hasText(expertId)) {
            return Optional.empty();
        }
        String id = expertId.strip();
        ExpertCatalogEntry cached = detailCache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<ExpertCatalogEntry> loaded = catalogClient.fetchExpertDetail(id);
        loaded.ifPresent(entry -> detailCache.put(id, entry));
        return loaded;
    }
}
