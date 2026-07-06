package com.sunshine.rag.admin.catalog;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.rag.admin.catalog.dto.CreateKbRequest;
import com.sunshine.rag.admin.catalog.dto.KbSummary;
import com.sunshine.rag.admin.config.ConfigVersionService;
import com.sunshine.rag.entity.KnowledgeBaseEntity;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ConfigVersionService configVersionService;

    public List<KbSummary> listByTenant(String tenantId) {
        String tid = normalizeTenant(tenantId);
        return knowledgeBaseRepository.findByTenantIdOrderByKbIdAsc(tid).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public KbSummary create(String tenantId, CreateKbRequest request) {
        String tid = normalizeTenant(tenantId);
        String kbId = requireKbId(request.kbId());
        if (knowledgeBaseRepository.findByTenantIdAndKbId(tid, kbId).isPresent()) {
            throw new BizException(RagErrorCode.KB_ALREADY_EXISTS);
        }
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setTenantId(tid);
        entity.setKbId(kbId);
        entity.setDisplayName(requireDisplayName(request.displayName()));
        entity.setDescription(trimOrNull(request.description()));
        entity.setDefault(false);
        entity.setStatus("active");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        KbSummary summary = toSummary(knowledgeBaseRepository.save(entity));
        configVersionService.provisionBundleForNewKb(tid, kbId);
        return summary;
    }

    @Transactional
    public KbSummary setDefault(String tenantId, String kbId) {
        String tid = normalizeTenant(tenantId);
        String kid = requireKbId(kbId);
        KnowledgeBaseEntity target = knowledgeBaseRepository.findByTenantIdAndKbId(tid, kid)
                .orElseThrow(() -> new BizException(RagErrorCode.KB_NOT_FOUND));
        knowledgeBaseRepository.findByTenantIdOrderByKbIdAsc(tid).forEach(kb -> {
            if (kb.isDefault()) {
                kb.setDefault(false);
                kb.setUpdatedAt(Instant.now());
                knowledgeBaseRepository.save(kb);
            }
        });
        target.setDefault(true);
        target.setUpdatedAt(Instant.now());
        return toSummary(knowledgeBaseRepository.save(target));
    }

    public KnowledgeBaseEntity requireKb(String tenantId, String kbId) {
        return knowledgeBaseRepository.findByTenantIdAndKbId(normalizeTenant(tenantId), requireKbId(kbId))
                .orElseThrow(() -> new BizException(RagErrorCode.KB_NOT_FOUND));
    }

    /** 租户默认知识库 id；无标记时 fallback default */
    public String getDefaultKbId(String tenantId) {
        return knowledgeBaseRepository.findByTenantIdAndIsDefaultTrue(normalizeTenant(tenantId))
                .map(KnowledgeBaseEntity::getKbId)
                .orElse("default");
    }

    /** 请求 kbId 优先，否则租户默认 */
    public String resolveKbId(String tenantId, String requestedKbId) {
        if (StringUtils.hasText(requestedKbId)) {
            return requestedKbId.strip();
        }
        return getDefaultKbId(tenantId);
    }

    private KbSummary toSummary(KnowledgeBaseEntity entity) {
        return new KbSummary(
                entity.getKbId(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.isDefault(),
                entity.getStatus());
    }

    public static String normalizeTenant(String tenantId) {
        return tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
    }

    public static String requireKbId(String kbId) {
        if (!StringUtils.hasText(kbId)) {
            throw new BizException(RagErrorCode.KB_NOT_FOUND);
        }
        return kbId.strip();
    }

    private static String requireDisplayName(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }
        return value.strip();
    }

    private static String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
