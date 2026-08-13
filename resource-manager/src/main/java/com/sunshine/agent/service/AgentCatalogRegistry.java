package com.sunshine.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.agent.dto.AgentCatalogEntry;
import com.sunshine.agent.dto.AgentCatalogIndexEntry;
import com.sunshine.agent.entity.AgentDefinitionEntity;
import com.sunshine.agent.entity.AgentSkillLinkEntity;
import com.sunshine.agent.repo.AgentDefinitionRepository;
import com.sunshine.agent.repo.AgentSkillLinkRepository;
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
public class AgentCatalogRegistry {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentDefinitionRepository definitionRepository;
    private final AgentSkillLinkRepository skillLinkRepository;
    private volatile Map<String, AgentCatalogEntry> entries = Map.of();

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<String, AgentCatalogEntry> merged = new LinkedHashMap<>();
        for (AgentDefinitionEntity def : definitionRepository.findByEnabledTrueOrderByIdAsc()) {
            merged.put(def.getId(), toEntry(def));
        }
        this.entries = Map.copyOf(merged);
        log.info("[AgentCatalogRegistry] loaded: {}", String.join(", ", entries.keySet()));
    }

    public List<AgentCatalogEntry> listEnabled() {
        return List.copyOf(entries.values());
    }

    public List<AgentCatalogIndexEntry> listEnabledIndex() {
        return entries.values().stream().map(AgentCatalogIndexEntry::from).toList();
    }

    public Optional<AgentCatalogEntry> find(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(agentId.strip()));
    }

    AgentCatalogEntry toEntry(AgentDefinitionEntity def) {
        List<String> skillIds = skillLinkRepository.findByIdAgentIdOrderByIdSkillIdAsc(def.getId()).stream()
                .map(link -> link.getId().getSkillId())
                .toList();
        return new AgentCatalogEntry(
                def.getId(),
                def.getDisplayName(),
                def.getDescription(),
                def.getSystemPrompt(),
                skillIds,
                parseTags(def.getTagsJson()),
                def.getToolsJson(),
                def.isEnabled(),
                def.getTenantId(),
                parseStringList(def.getKbScopeJson()),
                def.getDataScopeJson(),
                def.getPermissionsJson(),
                def.getModelConfigJson(),
                def.getMaxIters(),
                def.getMaxHandoffs(),
                AgentCatalogEntry.AgentSource.valueOf(def.getSource()),
                def.getAgentCardUrl(),
                def.getAuthConfigJson(),
                def.getEndpointOverride(),
                def.getKind() != null ? def.getKind() : "all");
    }

    private static List<String> parseTags(String json) {
        return parseStringList(json);
    }

    private static List<String> parseStringList(String json) {
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
