package com.sunshine.prompt.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.prompt.dto.PromptCreateRequest;
import com.sunshine.prompt.dto.PromptDetailResponse;
import com.sunshine.prompt.dto.PromptEnableRequest;
import com.sunshine.prompt.dto.PromptListItem;
import com.sunshine.prompt.dto.PromptPublishRequest;
import com.sunshine.prompt.dto.PromptRollbackRequest;
import com.sunshine.prompt.dto.PromptUpdateRequest;
import com.sunshine.prompt.dto.PromptVersionItem;
import com.sunshine.prompt.dto.PromptVersionRequest;
import com.sunshine.prompt.entity.PromptCatalogMetaEntity;
import com.sunshine.prompt.entity.PromptDefinitionEntity;
import com.sunshine.prompt.entity.PromptVersionEntity;
import com.sunshine.prompt.exception.PromptErrorCode;
import com.sunshine.prompt.repo.PromptCatalogMetaRepository;
import com.sunshine.prompt.repo.PromptDefinitionRepository;
import com.sunshine.prompt.repo.PromptVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptAdminService {
    private static final byte CATALOG_META_ID = 1;
    private final PromptDefinitionRepository definitionRepository;
    private final PromptVersionRepository versionRepository;
    private final PromptCatalogMetaRepository catalogMetaRepository;

    public List<PromptListItem> list(String kind, Boolean enabled) {
        List<PromptDefinitionEntity> defs = queryDefinitions(kind, enabled);
        long catalogVersion = currentCatalogVersion();
        return defs.stream()
                .sorted(Comparator.comparingInt(PromptDefinitionEntity::getPriority).reversed()
                        .thenComparing(PromptDefinitionEntity::getId))
                .map(def -> toListItem(def, catalogVersion))
                .toList();
    }

    public PromptDetailResponse get(String promptId) {
        return toDetail(requireDefinition(promptId));
    }

    @Transactional
    public PromptDetailResponse create(PromptCreateRequest request) {
        if (!StringUtils.hasText(request.id()) || !StringUtils.hasText(request.kind())
                || !StringUtils.hasText(request.displayName())) {
            throw new BizException(PromptErrorCode.ID_KIND_DISPLAY_NAME_REQUIRED);
        }
        String id = request.id().strip();
        if (definitionRepository.existsById(id)) {
            throw new BizException(PromptErrorCode.PROMPT_ALREADY_EXISTS);
        }
        Instant now = Instant.now();
        long catalogVersion = currentCatalogVersion();
        PromptDefinitionEntity def = new PromptDefinitionEntity();
        def.setId(id);
        def.setKind(request.kind().strip());
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : null);
        def.setEnabled(request.enabled() == null || request.enabled());
        def.setPriority(request.priority() != null ? request.priority() : 0);
        def.setActiveVersion(1);
        def.setCatalogVersion(catalogVersion);
        def.setCreatedAt(now);
        def.setUpdatedAt(now);
        definitionRepository.save(def);
        if (hasVersionContent(request.contentText(), request.contentJson())) {
            String status = normalizeStatus(request.status(), "draft");
            PromptVersionEntity version = newVersionEntity(id, 1, status, request.contentText(),
                    request.contentJson(), request.changeNote(), request.maintainer(), now);
            versionRepository.save(version);
            if ("published".equals(status)) {
                bumpCatalogAndSync(def);
            }
        }
        log.info("[PromptManager] created prompt={}", id);
        return toDetail(def);
    }

    @Transactional
    public PromptDetailResponse update(String promptId, PromptUpdateRequest request) {
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(PromptErrorCode.DISPLAY_NAME_REQUIRED);
        }
        PromptDefinitionEntity def = requireDefinition(promptId);
        checkOptimisticLock(def, request.expectedUpdatedAt());
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : null);
        if (request.priority() != null) {
            def.setPriority(request.priority());
        }
        bumpCatalogAndSync(def);
        log.info("[PromptManager] updated meta prompt={}", promptId);
        return toDetail(def);
    }

    @Transactional
    public PromptVersionItem addVersion(String promptId, PromptVersionRequest request) {
        PromptDefinitionEntity def = requireDefinition(promptId);
        checkOptimisticLock(def, request.expectedUpdatedAt());
        String status = normalizeStatus(request.status(), "draft");
        requireVersionContent(request.contentText(), request.contentJson());
        Instant now = Instant.now();
        // 草稿：已有 draft 则原地更新（对齐 Skills 单草稿）；published 始终新建版本
        if ("draft".equals(status)) {
            Optional<PromptVersionEntity> existingDraft =
                    versionRepository.findTopByPromptIdAndStatusOrderByVersionDesc(promptId, "draft");
            if (existingDraft.isPresent()) {
                PromptVersionEntity draft = existingDraft.get();
                draft.setContentText(request.contentText());
                draft.setContentJson(request.contentJson());
                if (request.changeNote() != null) {
                    draft.setChangeNote(request.changeNote().isBlank() ? null : request.changeNote().strip());
                }
                if (StringUtils.hasText(request.maintainer())) {
                    draft.setMaintainer(request.maintainer().strip());
                }
                versionRepository.save(draft);
                def.setUpdatedAt(now);
                definitionRepository.save(def);
                log.info("[PromptManager] updated draft prompt={} version={}", promptId, draft.getVersion());
                return toVersionItem(draft);
            }
        }
        int nextVersion = versionRepository.findTopByPromptIdOrderByVersionDesc(promptId)
                .map(v -> v.getVersion() + 1)
                .orElse(1);
        PromptVersionEntity version = newVersionEntity(promptId, nextVersion, status, request.contentText(),
                request.contentJson(), request.changeNote(), request.maintainer(), now);
        versionRepository.save(version);
        if ("published".equals(status)) {
            def.setActiveVersion(nextVersion);
            bumpCatalogAndSync(def);
        } else {
            def.setUpdatedAt(now);
            definitionRepository.save(def);
        }
        log.info("[PromptManager] added version prompt={} version={} status={}", promptId, nextVersion, status);
        return toVersionItem(version);
    }

    @Transactional
    public PromptDetailResponse publish(String promptId, PromptPublishRequest request) {
        PromptDefinitionEntity def = requireDefinition(promptId);
        checkOptimisticLock(def, request.expectedUpdatedAt());
        PromptVersionEntity version;
        if (request.version() != null) {
            version = versionRepository.findByPromptIdAndVersion(promptId, request.version())
                    .orElseThrow(() -> new BizException(PromptErrorCode.VERSION_NOT_FOUND));
        } else {
            version = versionRepository.findTopByPromptIdAndStatusOrderByVersionDesc(promptId, "draft")
                    .orElseThrow(() -> new BizException(PromptErrorCode.DRAFT_NOT_FOUND));
        }
        version.setStatus("published");
        if (StringUtils.hasText(request.maintainer())) {
            version.setMaintainer(request.maintainer().strip());
        }
        versionRepository.save(version);
        def.setActiveVersion(version.getVersion());
        bumpCatalogAndSync(def);
        log.info("[PromptManager] published prompt={} version={}", promptId, version.getVersion());
        return toDetail(def);
    }

    @Transactional
    public PromptDetailResponse rollback(String promptId, PromptRollbackRequest request) {
        PromptDefinitionEntity def = requireDefinition(promptId);
        checkOptimisticLock(def, request.expectedUpdatedAt());
        PromptVersionEntity version = versionRepository.findByPromptIdAndVersion(promptId, request.version())
                .orElseThrow(() -> new BizException(PromptErrorCode.VERSION_NOT_FOUND));
        if (!"published".equals(version.getStatus())) {
            throw new BizException(PromptErrorCode.ROLLBACK_REQUIRES_PUBLISHED);
        }
        def.setActiveVersion(version.getVersion());
        bumpCatalogAndSync(def);
        log.info("[PromptManager] rollback prompt={} version={}", promptId, request.version());
        return toDetail(def);
    }

    public List<PromptVersionItem> listVersions(String promptId) {
        requireDefinition(promptId);
        return versionRepository.findByPromptIdOrderByVersionDesc(promptId).stream()
                .map(this::toVersionItem)
                .toList();
    }

    @Transactional
    public PromptDetailResponse setEnabled(String promptId, PromptEnableRequest request) {
        PromptDefinitionEntity def = requireDefinition(promptId);
        checkOptimisticLock(def, request.expectedUpdatedAt());
        def.setEnabled(request.enabled());
        bumpCatalogAndSync(def);
        log.info("[PromptManager] set enabled prompt={} enabled={}", promptId, request.enabled());
        return toDetail(def);
    }

    private List<PromptDefinitionEntity> queryDefinitions(String kind, Boolean enabled) {
        if (StringUtils.hasText(kind) && enabled != null) {
            return definitionRepository.findByKindAndEnabled(kind.strip(), enabled);
        }
        if (StringUtils.hasText(kind)) {
            return definitionRepository.findByKind(kind.strip());
        }
        if (enabled != null) {
            return definitionRepository.findByEnabled(enabled);
        }
        return definitionRepository.findAll();
    }

    private PromptDefinitionEntity requireDefinition(String promptId) {
        return definitionRepository.findById(promptId.strip())
                .orElseThrow(() -> new BizException(PromptErrorCode.PROMPT_NOT_FOUND));
    }

    private void checkOptimisticLock(PromptDefinitionEntity def, Instant expectedUpdatedAt) {
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(def.getUpdatedAt())) {
            throw new BizException(CommonErrorCode.CONFLICT);
        }
    }

    private long bumpCatalogAndSync(PromptDefinitionEntity def) {
        PromptCatalogMetaEntity meta = requireCatalogMeta();
        long next = meta.getCatalogVersion() + 1;
        meta.setCatalogVersion(next);
        meta.setUpdatedAt(Instant.now());
        catalogMetaRepository.save(meta);
        def.setCatalogVersion(next);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        return next;
    }

    private long currentCatalogVersion() {
        return catalogMetaRepository.findById(CATALOG_META_ID)
                .map(PromptCatalogMetaEntity::getCatalogVersion)
                .orElse(1L);
    }

    private PromptCatalogMetaEntity requireCatalogMeta() {
        return catalogMetaRepository.findById(CATALOG_META_ID)
                .orElseThrow(() -> new BizException(CommonErrorCode.INTERNAL_ERROR));
    }

    private static String normalizeStatus(String status, String defaultStatus) {
        String normalized = StringUtils.hasText(status) ? status.strip().toLowerCase() : defaultStatus;
        if (!"draft".equals(normalized) && !"published".equals(normalized)) {
            throw new BizException(PromptErrorCode.INVALID_VERSION_STATUS);
        }
        return normalized;
    }

    private static void requireVersionContent(String contentText, String contentJson) {
        if (!hasVersionContent(contentText, contentJson)) {
            throw new BizException(PromptErrorCode.VERSION_CONTENT_REQUIRED);
        }
    }

    private static boolean hasVersionContent(String contentText, String contentJson) {
        return StringUtils.hasText(contentText) || StringUtils.hasText(contentJson);
    }

    private static PromptVersionEntity newVersionEntity(String promptId, int version, String status,
                                                        String contentText, String contentJson,
                                                        String changeNote, String maintainer, Instant createdAt) {
        PromptVersionEntity entity = new PromptVersionEntity();
        entity.setPromptId(promptId);
        entity.setVersion(version);
        entity.setStatus(status);
        entity.setContentText(contentText);
        entity.setContentJson(contentJson);
        entity.setChangeNote(changeNote != null ? changeNote.strip() : null);
        entity.setMaintainer(StringUtils.hasText(maintainer) ? maintainer.strip() : null);
        entity.setCreatedAt(createdAt);
        return entity;
    }

    private PromptListItem toListItem(PromptDefinitionEntity def, long catalogVersion) {
        return new PromptListItem(
                def.getId(),
                def.getKind(),
                def.getDisplayName(),
                def.isEnabled(),
                def.getPriority(),
                def.getActiveVersion(),
                catalogVersion,
                def.getUpdatedAt());
    }

    private PromptDetailResponse toDetail(PromptDefinitionEntity def) {
        long catalogVersion = currentCatalogVersion();
        PromptVersionItem active = versionRepository.findByPromptIdAndVersion(def.getId(), def.getActiveVersion())
                .map(this::toVersionItem)
                .orElse(null);
        return new PromptDetailResponse(
                def.getId(),
                def.getKind(),
                def.getDisplayName(),
                def.getDescription(),
                def.isEnabled(),
                def.getPriority(),
                def.getActiveVersion(),
                catalogVersion,
                def.getCreatedAt(),
                def.getUpdatedAt(),
                active);
    }

    private PromptVersionItem toVersionItem(PromptVersionEntity version) {
        return new PromptVersionItem(
                version.getVersion(),
                version.getStatus(),
                version.getContentText(),
                version.getContentJson(),
                version.getChangeNote(),
                version.getMaintainer(),
                version.getCreatedAt());
    }
}
