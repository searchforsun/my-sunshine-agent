package com.sunshine.rag.admin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.config.RagChunkProperties;
import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.entity.KbConfigOverrideEntity;
import com.sunshine.rag.repository.KbConfigOverrideRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EffectiveConfigService {

    private final RagSearchProperties searchProperties;
    private final RagRerankProperties rerankProperties;
    private final RagChunkProperties chunkProperties;
    private final KbConfigOverrideRepository overrideRepository;
    private final ObjectMapper objectMapper;

    public EffectiveRagConfig resolve(String tenantId, String kbId) {
        EffectiveRagConfig base = EffectiveRagConfig.fromNacos(searchProperties, rerankProperties, chunkProperties);
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        return overrideRepository.findByTenantIdAndKbId(tid, kid)
                .map(entity -> base.merge(parseOverride(entity.getOverrideJson())))
                .orElse(base);
    }

    private EffectiveRagConfig parseOverride(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, EffectiveRagConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("kb_config_override JSON 无效: " + e.getMessage(), e);
        }
    }
}
