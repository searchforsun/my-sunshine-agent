package com.sunshine.rag.admin.config;

import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EffectiveConfigService {

    private final EffectiveConfigResolver effectiveConfigResolver;

    /** @deprecated 过渡期兼容；新代码请用 {@link EffectiveConfigResolver} */
    public EffectiveRagConfig resolve(String tenantId, String kbId) {
        KnowledgeBaseService.normalizeTenant(tenantId);
        KnowledgeBaseService.requireKbId(kbId);
        return effectiveConfigResolver.resolve(tenantId, kbId).retrieval();
    }
}
