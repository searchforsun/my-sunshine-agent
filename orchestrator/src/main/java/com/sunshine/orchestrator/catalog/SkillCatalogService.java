package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.client.SkillCatalogClient;
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

/** 缓存 skill-manager catalog — 摘要常驻，正文按需拉取 */
@Slf4j
@Service
@RefreshScope
public class SkillCatalogService {

    private final SkillCatalogClient catalogClient;
    private volatile Map<String, SkillCatalogIndexEntry> indexEntries = Map.of();
    private final Map<String, SkillCatalogEntry> detailCache = new ConcurrentHashMap<>();

    public SkillCatalogService(SkillCatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<String, SkillCatalogIndexEntry> merged = new LinkedHashMap<>();
        for (SkillCatalogIndexEntry entry : catalogClient.fetchCatalogIndex()) {
            if (entry.id() != null) {
                merged.put(entry.id(), entry);
            }
        }
        this.indexEntries = Map.copyOf(merged);
        this.detailCache.clear();
        log.info("[SkillCatalogService] index loaded: {}", String.join(", ", indexEntries.keySet()));
    }

    public List<SkillCatalogIndexEntry> indexEntries() {
        return List.copyOf(indexEntries.values());
    }

    public Optional<SkillCatalogIndexEntry> findIndex(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(indexEntries.get(skillId.strip()));
    }

    public Optional<SkillCatalogEntry> find(String skillId) {
        if (!StringUtils.hasText(skillId)) {
            return Optional.empty();
        }
        String id = skillId.strip();
        SkillCatalogEntry cached = detailCache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<SkillCatalogEntry> loaded = catalogClient.fetchSkillDetail(id);
        loaded.ifPresent(entry -> detailCache.put(id, entry));
        return loaded;
    }

    public String overlayOrEmpty(String skillId) {
        return find(skillId).map(SkillCatalogEntry::systemOverlay).orElse("");
    }

    /** @deprecated 使用 {@link #indexEntries()}；不含 overlay */
    @Deprecated
    public List<SkillCatalogEntry> allEntries() {
        return indexEntries().stream()
                .map(idx -> new SkillCatalogEntry(
                        idx.id(), idx.displayName(), idx.description(), "",
                        idx.version(), idx.enabled()))
                .toList();
    }
}
