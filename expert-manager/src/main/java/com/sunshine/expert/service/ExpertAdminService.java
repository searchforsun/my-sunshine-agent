package com.sunshine.expert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.expert.dto.ExpertCatalogEntry;
import com.sunshine.expert.dto.ExpertCatalogIndexEntry;
import com.sunshine.expert.dto.ExpertCreateRequest;
import com.sunshine.expert.dto.ExpertUpdateRequest;
import com.sunshine.expert.entity.ExpertDefinitionEntity;
import com.sunshine.expert.entity.ExpertSkillLinkEntity;
import com.sunshine.expert.entity.ExpertSkillLinkId;
import com.sunshine.expert.exception.ExpertErrorCode;
import com.sunshine.expert.repo.ExpertDefinitionRepository;
import com.sunshine.expert.repo.ExpertSkillLinkRepository;
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
public class ExpertAdminService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ExpertDefinitionRepository definitionRepository;
    private final ExpertSkillLinkRepository skillLinkRepository;
    private final ExpertCatalogRegistry catalogRegistry;

    public List<ExpertCatalogEntry> listAll() {
        return definitionRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .map(catalogRegistry::toEntry)
                .toList();
    }

    public List<ExpertCatalogIndexEntry> listCatalogIndex() {
        return catalogRegistry.listEnabledIndex();
    }

    public Optional<ExpertCatalogEntry> findCatalogEntry(String expertId) {
        return catalogRegistry.find(expertId);
    }

    @Transactional
    public ExpertCatalogEntry create(ExpertCreateRequest request) {
        if (!StringUtils.hasText(request.id()) || !StringUtils.hasText(request.displayName())) {
            throw new BizException(ExpertErrorCode.ID_DISPLAY_NAME_REQUIRED);
        }
        if (!StringUtils.hasText(request.systemPrompt())) {
            throw new BizException(ExpertErrorCode.SYSTEM_PROMPT_REQUIRED);
        }
        String id = request.id().strip();
        if (definitionRepository.existsById(id)) {
            throw new BizException(ExpertErrorCode.EXPERT_ALREADY_EXISTS);
        }
        Instant now = Instant.now();
        ExpertDefinitionEntity def = new ExpertDefinitionEntity();
        def.setId(id);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
        def.setSystemPrompt(request.systemPrompt().strip());
        def.setEnabled(true);
        def.setTagsJson("[]");
        def.setToolsJson("[\"*\"]");
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        definitionRepository.save(def);
        replaceSkillLinks(id, request.skillIds());
        catalogRegistry.refresh();
        return catalogRegistry.toEntry(def);
    }

    @Transactional
    public ExpertCatalogEntry update(String expertId, ExpertUpdateRequest request) {
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(ExpertErrorCode.DISPLAY_NAME_REQUIRED);
        }
        if (!StringUtils.hasText(request.systemPrompt())) {
            throw new BizException(ExpertErrorCode.SYSTEM_PROMPT_REQUIRED);
        }
        ExpertDefinitionEntity def = requireDefinition(expertId);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
        def.setSystemPrompt(request.systemPrompt().strip());
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        replaceSkillLinks(expertId, request.skillIds());
        catalogRegistry.refresh();
        return catalogRegistry.toEntry(def);
    }

    @Transactional
    public ExpertCatalogEntry setEnabled(String expertId, boolean enabled) {
        ExpertDefinitionEntity def = requireDefinition(expertId);
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
            throw new BizException(ExpertErrorCode.EXPERT_NOT_FOUND);
        }
        skillLinkRepository.deleteByIdExpertId(id);
        definitionRepository.deleteById(id);
        catalogRegistry.refresh();
        log.info("[ExpertManager] deleted expert={}", id);
    }

    private ExpertDefinitionEntity requireDefinition(String expertId) {
        return definitionRepository.findById(expertId.strip())
                .orElseThrow(() -> new BizException(ExpertErrorCode.EXPERT_NOT_FOUND));
    }

    private void replaceSkillLinks(String expertId, List<String> skillIds) {
        skillLinkRepository.deleteByIdExpertId(expertId);
        if (skillIds == null || skillIds.isEmpty()) {
            return;
        }
        List<ExpertSkillLinkEntity> links = new ArrayList<>();
        for (String raw : skillIds) {
            if (!StringUtils.hasText(raw)) {
                continue;
            }
            ExpertSkillLinkEntity link = new ExpertSkillLinkEntity();
            ExpertSkillLinkId id = new ExpertSkillLinkId();
            id.setExpertId(expertId);
            id.setSkillId(raw.strip());
            link.setId(id);
            links.add(link);
        }
        skillLinkRepository.saveAll(links);
    }
}
