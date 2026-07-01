package com.sunshine.rag.admin.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.entity.KbConfigOverrideEntity;
import com.sunshine.rag.repository.KbConfigOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KbConfigOverrideService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final KnowledgeBaseService knowledgeBaseService;
    private final KbConfigOverrideRepository overrideRepository;
    private final EffectiveConfigService effectiveConfigService;
    private final ObjectMapper objectMapper;

    public EffectiveRagConfig getEffective(String tenantId, String kbId) {
        knowledgeBaseService.requireKb(tenantId, kbId);
        return effectiveConfigService.resolve(tenantId, kbId);
    }

    @Transactional
    public EffectiveRagConfig putOverride(String tenantId, String kbId, Map<String, Object> patch) {
        knowledgeBaseService.requireKb(tenantId, kbId);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        Map<String, Object> merged = new LinkedHashMap<>(loadMap(
                overrideRepository.findByTenantIdAndKbId(tid, kid).map(KbConfigOverrideEntity::getOverrideJson).orElse("{}")));
        if (patch != null) {
            patch.forEach((key, value) -> {
                if (value == null) {
                    merged.remove(key);
                } else {
                    merged.put(key, value);
                }
            });
        }
        if (merged.isEmpty()) {
            overrideRepository.findByTenantIdAndKbId(tid, kid).ifPresent(overrideRepository::delete);
            return effectiveConfigService.resolve(tid, kid);
        }
        KbConfigOverrideEntity entity = overrideRepository.findByTenantIdAndKbId(tid, kid)
                .orElseGet(KbConfigOverrideEntity::new);
        entity.setTenantId(tid);
        entity.setKbId(kid);
        entity.setOverrideJson(writeMap(merged));
        entity.setUpdatedAt(Instant.now());
        overrideRepository.save(entity);
        return effectiveConfigService.resolve(tid, kid);
    }

    @Transactional
    public EffectiveRagConfig deleteField(String tenantId, String kbId, String field) {
        knowledgeBaseService.requireKb(tenantId, kbId);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        return overrideRepository.findByTenantIdAndKbId(tid, kid)
                .map(entity -> {
                    Map<String, Object> merged = new LinkedHashMap<>(loadMap(entity.getOverrideJson()));
                    merged.remove(field);
                    if (merged.isEmpty()) {
                        overrideRepository.delete(entity);
                    } else {
                        entity.setOverrideJson(writeMap(merged));
                        entity.setUpdatedAt(Instant.now());
                        overrideRepository.save(entity);
                    }
                    return effectiveConfigService.resolve(tid, kid);
                })
                .orElseGet(() -> effectiveConfigService.resolve(tid, kid));
    }

    private Map<String, Object> loadMap(String json) {
        try {
            return objectMapper.readValue(json != null ? json : "{}", MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("override_json 无效: " + e.getMessage(), e);
        }
    }

    private String writeMap(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("override_json 序列化失败: " + e.getMessage(), e);
        }
    }
}
