package com.sunshine.rag.admin.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.admin.config.dto.ConfigDraftSummary;
import com.sunshine.rag.entity.ConfigDraftEntity;
import com.sunshine.rag.repository.ConfigDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ConfigDraftService {

    private static final String STATUS_DRAFT = "draft";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ConfigDraftRepository draftRepository;
    private final ObjectMapper objectMapper;

    public List<ConfigDraftSummary> listDrafts(String tenantId) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        return draftRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tid, STATUS_DRAFT).stream()
                .map(this::toSummary)
                .toList();
    }

    public Optional<ConfigDraftSummary> getDraft(String tenantId, String scope) {
        ConfigScope.require(scope);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        return draftRepository
                .findFirstByTenantIdAndScopeAndStatusOrderByCreatedAtDesc(tid, scope, STATUS_DRAFT)
                .map(this::toSummary);
    }

    @Transactional
    public ConfigDraftSummary saveDraft(
            String tenantId, String scope, Map<String, Object> payload, String createdBy) {
        ConfigScope.require(scope);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        Map<String, Object> body = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        ConfigDraftEntity entity = draftRepository
                .findFirstByTenantIdAndScopeAndStatusOrderByCreatedAtDesc(tid, scope, STATUS_DRAFT)
                .orElseGet(ConfigDraftEntity::new);
        if (entity.getId() == null) {
            entity.setTenantId(tid);
            entity.setScope(scope);
            entity.setStatus(STATUS_DRAFT);
            entity.setCreatedAt(Instant.now());
        }
        entity.setPayloadJson(writeMap(body));
        entity.setCreatedBy(createdBy);
        return toSummary(draftRepository.save(entity));
    }

    private ConfigDraftSummary toSummary(ConfigDraftEntity entity) {
        return new ConfigDraftSummary(
                entity.getId(),
                entity.getScope(),
                loadMap(entity.getPayloadJson()),
                entity.getStatus(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getPublishedAt());
    }

    private Map<String, Object> loadMap(String json) {
        try {
            return objectMapper.readValue(json != null ? json : "{}", MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("config_draft payload 无效: " + e.getMessage(), e);
        }
    }

    private String writeMap(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("config_draft payload 序列化失败: " + e.getMessage(), e);
        }
    }
}
