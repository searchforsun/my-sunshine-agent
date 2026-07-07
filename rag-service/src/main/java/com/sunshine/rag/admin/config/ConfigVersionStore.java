package com.sunshine.rag.admin.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.util.VersionTimestampDedup;
import com.sunshine.rag.admin.catalog.KnowledgeBaseService;
import com.sunshine.rag.entity.RagConfigBundleEntity;
import com.sunshine.rag.entity.RagConfigVersionEntity;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.repository.KnowledgeBaseRepository;
import com.sunshine.rag.repository.RagConfigBundleRepository;
import com.sunshine.rag.repository.RagConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 配置版本 bundle / payload 持久化辅助 */
@Component
@RequiredArgsConstructor
class ConfigVersionStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final RagConfigBundleRepository bundleRepository;
    private final RagConfigVersionRepository versionRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final ObjectMapper objectMapper;

    RagConfigBundleEntity requireBundle(String tenantId, String kbId) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        requireKb(tid, kid);
        return bundleRepository.findByTenantIdAndKbId(tid, kid)
                .orElseThrow(() -> new BizException(RagErrorCode.CONFIG_BUNDLE_NOT_FOUND));
    }

    RagConfigBundleEntity lockBundle(String tenantId, String kbId) {
        String tid = KnowledgeBaseService.normalizeTenant(tenantId);
        String kid = KnowledgeBaseService.requireKbId(kbId);
        requireKb(tid, kid);
        return bundleRepository.findByTenantIdAndKbIdForUpdate(tid, kid)
                .orElseThrow(() -> new BizException(RagErrorCode.CONFIG_BUNDLE_NOT_FOUND));
    }

    Optional<RagConfigVersionEntity> findPipelineVersion(Long bundleId) {
        return versionRepository.findByBundleIdAndStatusIn(bundleId, List.of(
                        ConfigVersionStatus.DRAFT,
                        ConfigVersionStatus.PENDING_EVAL,
                        ConfigVersionStatus.EVALUATING,
                        ConfigVersionStatus.EVAL_PASSED,
                        ConfigVersionStatus.EVAL_FAILED))
                .stream()
                .findFirst();
    }

    RagConfigVersionEntity requireDraftPointer(RagConfigBundleEntity bundle) {
        if (bundle.getDraftVersionId() == null) {
            throw new ConfigVersionConflictException("当前无草稿，请从生效版本「复制为草稿」");
        }
        RagConfigVersionEntity draft = requireVersion(bundle.getDraftVersionId());
        if (!ConfigVersionStatus.isDraft(draft.getStatus())) {
            throw new ConfigVersionConflictException("当前无可用草稿，请从生效版本「复制为草稿」");
        }
        return draft;
    }

    RagConfigVersionEntity requireVersionInBundle(Long versionId, RagConfigBundleEntity bundle) {
        return versionRepository.findByIdAndBundleId(versionId, bundle.getId())
                .orElseThrow(() -> new IllegalArgumentException("版本不存在: " + versionId));
    }

    RagConfigVersionEntity requireVersion(Long versionId) {
        if (versionId == null) {
            throw new IllegalStateException("配置版本指针缺失");
        }
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("配置版本不存在: " + versionId));
    }

    RagConfigVersionEntity resolveVersion(RagConfigBundleEntity bundle, String mode, Long versionId) {
        if (mode == null || mode.isBlank() || "published".equalsIgnoreCase(mode)) {
            return requireVersion(bundle.getActivePublishedVersionId());
        }
        if ("draft".equalsIgnoreCase(mode)) {
            RagConfigVersionEntity draft = requireDraftPointer(bundle);
            if (!ConfigVersionStatus.isDraft(draft.getStatus())) {
                throw new IllegalArgumentException("当前无可用草稿，draft 指针状态: " + draft.getStatus());
            }
            return draft;
        }
        if ("version".equalsIgnoreCase(mode)) {
            if (versionId == null) {
                throw new IllegalArgumentException("version 模式需要 versionId");
            }
            RagConfigVersionEntity version = requireVersionInBundle(versionId, bundle);
            if (ConfigVersionStatus.isDraft(version.getStatus())) {
                throw new IllegalArgumentException("草稿版本不可作为应用配置");
            }
            return version;
        }
        throw new IllegalArgumentException("未知 mode: " + mode);
    }

    RagConfigVersionEntity newVersion(
            RagConfigBundleEntity bundle,
            int versionNo,
            String status,
            Map<String, Object> payload,
            String createdBy) {
        RagConfigVersionEntity version = new RagConfigVersionEntity();
        version.setBundleId(bundle.getId());
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setPayloadJson(toJson(payload));
        version.setCreatedBy(createdBy);
        version.setCreatedAt(uniqueInstant(bundle.getId(), Instant.now()));
        return version;
    }

    Instant uniqueInstant(Long bundleId, Instant candidate) {
        List<Instant> existing = versionRepository.findByBundleIdOrderByVersionNoDesc(bundleId).stream()
                .flatMap(v -> java.util.stream.Stream.of(v.getCreatedAt(), v.getPublishedAt()))
                .filter(java.util.Objects::nonNull)
                .toList();
        return VersionTimestampDedup.uniqueInstant(candidate, existing);
    }

    int nextVersionNo(Long bundleId) {
        return versionRepository.findFirstByBundleIdOrderByVersionNoDesc(bundleId)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
    }

    void touchBundle(RagConfigBundleEntity bundle) {
        bundle.setUpdatedAt(Instant.now());
        bundleRepository.save(bundle);
    }

    void supersedeActive(RagConfigBundleEntity bundle) {
        if (bundle.getActivePublishedVersionId() == null) {
            return;
        }
        versionRepository.findById(bundle.getActivePublishedVersionId()).ifPresent(active -> {
            if (ConfigVersionStatus.isActive(active.getStatus())) {
                active.setStatus(ConfigVersionStatus.SUPERSEDED);
                versionRepository.save(active);
            }
        });
    }

    void ensureDraftAfterActivate(RagConfigBundleEntity bundle, RagConfigVersionEntity active) {
        if (active.getId().equals(bundle.getDraftVersionId())) {
            bundle.setDraftVersionId(null);
        }
    }

    Map<String, Object> loadDefaultSeedPayload() {
        try (InputStream in = getClass().getResourceAsStream("/rag/defaults/config-seed.json")) {
            if (in == null) {
                throw new IllegalStateException("config-seed.json 缺失");
            }
            return objectMapper.readValue(in, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("加载 config-seed.json 失败: " + e.getMessage(), e);
        }
    }

    Map<String, Object> parsePayload(RagConfigVersionEntity version) {
        try {
            return objectMapper.readValue(version.getPayloadJson(), MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("payload_json 无效: " + e.getMessage(), e);
        }
    }

    Map<String, Object> deepCopy(Map<String, Object> source) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(source), MAP_TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>(source);
        }
    }

    String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("payload 序列化失败: " + e.getMessage(), e);
        }
    }

    void requireKb(String tenantId, String kbId) {
        knowledgeBaseRepository.findByTenantIdAndKbId(tenantId, kbId)
                .orElseThrow(() -> new BizException(RagErrorCode.KB_NOT_FOUND));
    }
}
