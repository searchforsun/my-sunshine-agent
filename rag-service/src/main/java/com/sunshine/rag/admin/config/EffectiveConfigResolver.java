package com.sunshine.rag.admin.config;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.exception.RagErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 DB 配置版本解析运行时有效配置。
 * PRODUCTION 走本地缓存；publish/activate 后须 {@link #invalidate(String, String)}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffectiveConfigResolver {

    private final ConfigVersionService configVersionService;
    private final ConcurrentHashMap<String, ResolvedKbConfig> productionCache = new ConcurrentHashMap<>();

    public ResolvedKbConfig resolve(String tenantId, String kbId) {
        return resolve(tenantId, kbId, ConfigResolveMode.PRODUCTION, null);
    }

    public ResolvedKbConfig resolve(String tenantId, String kbId, ConfigResolveMode mode, Long versionId) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        if (mode == ConfigResolveMode.PRODUCTION) {
            String cacheKey = cacheKey(tid, kid);
            return productionCache.computeIfAbsent(cacheKey, key -> load(tid, kid, mode, null));
        }
        return load(tid, kid, mode, versionId);
    }

    /** smoke / 预览：直接由 payload 物化，不读写 draft 指针 */
    public ResolvedKbConfig resolvePayload(String tenantId, String kbId, Map<String, Object> payload) {
        return ConfigBundlePayload.toResolvedKbConfig(payload);
    }

    public void invalidate(String tenantId, String kbId) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        productionCache.remove(cacheKey(tid, kid));
        log.info("[RAG] effective config cache invalidated tenant={} kb={}", tid, kid);
    }

    @EventListener
    public void onConfigChanged(KbConfigChangedEvent event) {
        invalidate(event.tenantId(), event.kbId());
    }

    private ResolvedKbConfig load(String tenantId, String kbId, ConfigResolveMode mode, Long versionId) {
        String modeKey = switch (mode) {
            case PRODUCTION -> "published";
            case DRAFT -> "draft";
            case VERSION -> "version";
        };
        Map<String, Object> payload = configVersionService.getEffective(tenantId, kbId, modeKey, versionId);
        return ConfigBundlePayload.toResolvedKbConfig(payload);
    }

    private static String cacheKey(String tenantId, String kbId) {
        return tenantId + "|" + kbId;
    }
}
