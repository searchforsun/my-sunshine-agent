package com.sunshine.skill.service;

import com.sunshine.skill.dto.SkillCatalogEntry;
import com.sunshine.skill.dto.SkillCatalogIndexEntry;
import com.sunshine.skill.entity.SkillDefinitionEntity;
import com.sunshine.skill.entity.SkillVersionEntity;
import com.sunshine.skill.repo.SkillDefinitionRepository;
import com.sunshine.skill.repo.SkillVersionRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 内存 Catalog 缓存 — 供 runtime 拉取 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillCatalogRegistry {

    private final SkillDefinitionRepository definitionRepository;
    private final SkillVersionRepository versionRepository;
    private volatile Map<String, SkillCatalogEntry> entries = Map.of();

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<String, SkillCatalogEntry> merged = new LinkedHashMap<>();
        for (SkillDefinitionEntity def : definitionRepository.findByEnabledTrueOrderByIdAsc()) {
            versionRepository.findBySkillIdAndVersion(def.getId(), def.getActiveVersion())
                    .filter(ver -> "published".equals(ver.getStatus()))
                    .ifPresent(ver -> merged.put(def.getId(), toEntry(def, ver)));
        }
        this.entries = Map.copyOf(merged);
        log.info("[SkillCatalogRegistry] loaded: {}", String.join(", ", entries.keySet()));
    }

    public List<SkillCatalogEntry> listEnabled() {
        return List.copyOf(entries.values());
    }

    public List<SkillCatalogIndexEntry> listEnabledIndex() {
        return entries.values().stream().map(SkillCatalogIndexEntry::from).toList();
    }

    public Optional<SkillCatalogEntry> find(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(skillId.strip()));
    }

    private static SkillCatalogEntry toEntry(SkillDefinitionEntity def, SkillVersionEntity ver) {
        return new SkillCatalogEntry(
                def.getId(),
                def.getDisplayName(),
                def.getDescription(),
                ver.getSystemOverlay(),
                ver.getVersion(),
                def.isEnabled(),
                ver.getCreatedAt(),
                ver.getMaintainer(),
                true);
    }
}
