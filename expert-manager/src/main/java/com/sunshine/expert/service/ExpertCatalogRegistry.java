package com.sunshine.expert.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.expert.dto.ExpertCatalogEntry;
import com.sunshine.expert.dto.ExpertCatalogIndexEntry;
import com.sunshine.expert.entity.ExpertDefinitionEntity;
import com.sunshine.expert.entity.ExpertSkillLinkEntity;
import com.sunshine.expert.repo.ExpertDefinitionRepository;
import com.sunshine.expert.repo.ExpertSkillLinkRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExpertCatalogRegistry {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ExpertDefinitionRepository definitionRepository;
    private final ExpertSkillLinkRepository skillLinkRepository;
    private volatile Map<String, ExpertCatalogEntry> entries = Map.of();

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<String, ExpertCatalogEntry> merged = new LinkedHashMap<>();
        for (ExpertDefinitionEntity def : definitionRepository.findByEnabledTrueOrderByIdAsc()) {
            merged.put(def.getId(), toEntry(def));
        }
        this.entries = Map.copyOf(merged);
        log.info("[ExpertCatalogRegistry] loaded: {}", String.join(", ", entries.keySet()));
    }

    public List<ExpertCatalogEntry> listEnabled() {
        return List.copyOf(entries.values());
    }

    public List<ExpertCatalogIndexEntry> listEnabledIndex() {
        return entries.values().stream().map(ExpertCatalogIndexEntry::from).toList();
    }

    public Optional<ExpertCatalogEntry> find(String expertId) {
        if (expertId == null || expertId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(expertId.strip()));
    }

    ExpertCatalogEntry toEntry(ExpertDefinitionEntity def) {
        List<String> skillIds = skillLinkRepository.findByIdExpertIdOrderByIdSkillIdAsc(def.getId()).stream()
                .map(link -> link.getId().getSkillId())
                .toList();
        return new ExpertCatalogEntry(
                def.getId(),
                def.getDisplayName(),
                def.getDescription(),
                def.getSystemPrompt(),
                skillIds,
                parseTags(def.getTagsJson()),
                def.getToolsJson(),
                def.isEnabled());
    }

    private static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
