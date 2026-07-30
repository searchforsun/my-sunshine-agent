package com.sunshine.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.agent.dto.AgentCatalogEntry;
import com.sunshine.agent.dto.AgentCatalogIndexEntry;
import com.sunshine.agent.dto.AgentCreateRequest;
import com.sunshine.agent.dto.AgentUpdateRequest;
import com.sunshine.agent.entity.AgentDefinitionEntity;
import com.sunshine.agent.entity.AgentSkillLinkEntity;
import com.sunshine.agent.entity.AgentSkillLinkId;
import com.sunshine.agent.exception.AgentErrorCode;
import com.sunshine.agent.repo.AgentDefinitionRepository;
import com.sunshine.agent.repo.AgentSkillLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAdminService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AgentDefinitionRepository definitionRepository;
    private final AgentSkillLinkRepository skillLinkRepository;
    private final AgentCatalogRegistry catalogRegistry;

    public List<AgentCatalogEntry> listAll() {
        return definitionRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .map(catalogRegistry::toEntry)
                .toList();
    }

    public List<AgentCatalogIndexEntry> listCatalogIndex() {
        return catalogRegistry.listEnabledIndex();
    }

    public Optional<AgentCatalogEntry> findCatalogEntry(String expertId) {
        return catalogRegistry.find(expertId);
    }

    @Transactional
    public AgentCatalogEntry create(AgentCreateRequest request) {
        if (!StringUtils.hasText(request.id()) || !StringUtils.hasText(request.displayName())) {
            throw new BizException(AgentErrorCode.ID_DISPLAY_NAME_REQUIRED);
        }
        if (!StringUtils.hasText(request.systemPrompt())) {
            throw new BizException(AgentErrorCode.SYSTEM_PROMPT_REQUIRED);
        }
        String id = request.id().strip();
        if (definitionRepository.existsById(id)) {
            throw new BizException(AgentErrorCode.EXPERT_ALREADY_EXISTS);
        }
        Instant now = Instant.now();
        AgentDefinitionEntity def = new AgentDefinitionEntity();
        def.setId(id);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
        def.setSystemPrompt(request.systemPrompt().strip());
        def.setEnabled(true);
        def.setTagsJson("[]");
        def.setToolsJson(serializeToolIds(request.toolIds()));
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        definitionRepository.save(def);
        replaceSkillLinks(id, request.skillIds());
        catalogRegistry.refresh();
        return catalogRegistry.toEntry(def);
    }

    @Transactional
    public AgentCatalogEntry update(String expertId, AgentUpdateRequest request) {
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(AgentErrorCode.DISPLAY_NAME_REQUIRED);
        }
        if (!StringUtils.hasText(request.systemPrompt())) {
            throw new BizException(AgentErrorCode.SYSTEM_PROMPT_REQUIRED);
        }
        AgentDefinitionEntity def = requireDefinition(expertId);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
        def.setSystemPrompt(request.systemPrompt().strip());
        def.setToolsJson(serializeToolIds(request.toolIds()));
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        replaceSkillLinks(expertId, request.skillIds());
        catalogRegistry.refresh();
        return catalogRegistry.toEntry(def);
    }

    @Transactional
    public AgentCatalogEntry setEnabled(String expertId, boolean enabled) {
        AgentDefinitionEntity def = requireDefinition(expertId);
        def.setEnabled(enabled);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogRegistry.refresh();
        return catalogRegistry.toEntry(def);
    }

    @Transactional
    public void delete(String expertId) {
        String id = expertId.strip();
        if (!definitionRepository.existsById(id)) {
            throw new BizException(AgentErrorCode.EXPERT_NOT_FOUND);
        }
        skillLinkRepository.deleteByIdExpertId(id);
        definitionRepository.deleteById(id);
        catalogRegistry.refresh();
        log.info("[ExpertManager] deleted expert={}", id);
    }

    private AgentDefinitionEntity requireDefinition(String expertId) {
        return definitionRepository.findById(expertId.strip())
                .orElseThrow(() -> new BizException(AgentErrorCode.EXPERT_NOT_FOUND));
    }

    private String serializeToolIds(List<String> toolIds) {
        try {
            List<String> clean = new ArrayList<>();
            if (toolIds != null) {
                for (String id : toolIds) {
                    if (StringUtils.hasText(id) && !"*".equals(id.strip())) {
                        clean.add(id.strip());
                    }
                }
            }
            return MAPPER.writeValueAsString(clean);
        } catch (Exception e) {
            return "[]";
        }
    }

    private void replaceSkillLinks(String expertId, List<String> skillIds) {
        skillLinkRepository.deleteByIdExpertId(expertId);
        if (skillIds == null || skillIds.isEmpty()) {
            return;
        }
        List<AgentSkillLinkEntity> links = new ArrayList<>();
        for (String raw : skillIds) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            AgentSkillLinkEntity link = new AgentSkillLinkEntity();
            AgentSkillLinkId id = new AgentSkillLinkId();
            id.setExpertId(expertId);
            id.setSkillId(raw.strip());
            link.setId(id);
            links.add(link);
        }
        skillLinkRepository.saveAll(links);
    }
}
