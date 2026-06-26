package com.sunshine.skill.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.skill.dto.SkillCatalogEntry;
import com.sunshine.skill.dto.SkillCatalogIndexEntry;
import com.sunshine.skill.dto.SkillCreateRequest;
import com.sunshine.skill.dto.SkillUpdateRequest;
import com.sunshine.skill.exception.SkillErrorCode;
import com.sunshine.skill.entity.SkillDefinitionEntity;
import com.sunshine.skill.entity.SkillVersionEntity;
import com.sunshine.skill.repo.SkillDefinitionRepository;
import com.sunshine.skill.repo.SkillVersionRepository;
import com.sunshine.skill.skillmd.SkillMdDocument;
import com.sunshine.skill.skillmd.SkillPackage;
import com.sunshine.skill.storage.SkillStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillAdminService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillDefinitionRepository definitionRepository;
    private final SkillVersionRepository versionRepository;
    private final SkillStorageService skillStorageService;
    private final SkillCatalogRegistry catalogRegistry;

    public List<SkillCatalogEntry> listAll() {
        return definitionRepository.findAll().stream()
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .map(this::toCatalogEntry)
                .flatMap(Optional::stream)
                .toList();
    }

    public List<SkillCatalogEntry> listCatalog() {
        return catalogRegistry.listEnabled();
    }

    public List<SkillCatalogIndexEntry> listCatalogIndex() {
        return catalogRegistry.listEnabledIndex();
    }

    public Optional<SkillCatalogEntry> findCatalogEntry(String skillId) {
        return catalogRegistry.find(skillId);
    }

    @Transactional
    public SkillCatalogEntry create(SkillCreateRequest request) {
        if (!StringUtils.hasText(request.id()) || !StringUtils.hasText(request.displayName())) {
            throw new BizException(SkillErrorCode.ID_DISPLAY_NAME_REQUIRED);
        }
        String id = request.id().strip();
        if (definitionRepository.existsById(id)) {
            throw new BizException(SkillErrorCode.SKILL_ALREADY_EXISTS);
        }
        SkillDefinitionEntity def = new SkillDefinitionEntity();
        def.setId(id);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
        def.setEnabled(false);
        def.setActiveVersion(1);
        definitionRepository.save(def);

        SkillVersionEntity version = new SkillVersionEntity();
        version.setSkillId(id);
        version.setVersion(1);
        version.setSystemOverlay("请上传标准 SKILL.md（含 YAML frontmatter：name、description）。");
        version.setToolsJson("[]");
        version.setMaxIters(4);
        version.setSideEffect("read");
        version.setSandbox("none");
        version.setReferencesJson("[]");
        version.setScriptsJson("[]");
        version.setStatus("draft");
        versionRepository.save(version);
        catalogRegistry.refresh();
        return toCatalogEntry(def).orElseThrow();
    }

    @Transactional
    public SkillCatalogEntry updateMeta(String skillId, SkillUpdateRequest request) {
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(SkillErrorCode.DISPLAY_NAME_REQUIRED);
        }
        SkillDefinitionEntity def = requireDefinition(skillId);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description() != null ? request.description().strip() : "");
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogRegistry.refresh();
        log.info("[SkillManager] updated meta skill={}", skillId);
        return toCatalogEntry(def).orElseThrow();
    }

    @Transactional
    public SkillCatalogEntry uploadPackage(String skillId, SkillPackage pkg, String maintainer) {
        SkillDefinitionEntity def = requireDefinition(skillId);
        SkillMdDocument doc = pkg.document();
        validateSkillName(skillId, doc.name());
        applyDefinitionMeta(def, doc);
        String operator = resolveMaintainer(maintainer);
        Optional<SkillVersionEntity> latest = versionRepository.findTopBySkillIdOrderByVersionDesc(skillId);
        Optional<SkillVersionEntity> draft = findDraftVersion(skillId);
        SkillVersionEntity version;
        int targetVersion;
        if (draft.isPresent()) {
            if (isEmptyDraft(draft.get())) {
                version = draft.get();
                targetVersion = version.getVersion();
            } else if (latest.isPresent() && draft.get().getVersion() == latest.get().getVersion()) {
                version = draft.get();
                targetVersion = version.getVersion();
                skillStorageService.deletePackage(skillId, targetVersion, version.getStoragePath());
            } else {
                throw draftAlreadyExists();
            }
        } else {
            targetVersion = latest.map(v -> v.getVersion() + 1).orElse(1);
            version = newVersionEntity(skillId, targetVersion);
        }
        String storagePath = skillStorageService.persistPackage(skillId, targetVersion, pkg);
        version.setSystemOverlay(doc.body());
        version.setReferencesJson(writePathList(listPrefixedPaths(pkg.files(), "references/")));
        version.setScriptsJson(writePathList(listPrefixedPaths(pkg.files(), "scripts/")));
        version.setStoragePath(storagePath);
        version.setStatus("draft");
        version.setMaintainer(operator);
        version.setCreatedAt(Instant.now());
        versionRepository.save(version);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogRegistry.refresh();
        log.info("[SkillManager] uploaded draft skill={} version={}", skillId, targetVersion);
        return toCatalogEntry(def).orElseThrow();
    }

    /** 基于已有版本复制为新草稿，便于在线编辑而无需重新上传整包 */
    @Transactional
    public SkillCatalogEntry forkVersion(String skillId, int sourceVersion, String maintainer) {
        SkillDefinitionEntity def = requireDefinition(skillId);
        SkillVersionEntity source = versionRepository.findBySkillIdAndVersion(skillId, sourceVersion)
                .orElseThrow(() -> new BizException(SkillErrorCode.VERSION_NOT_FOUND));
        if (!StringUtils.hasText(source.getStoragePath())) {
            throw new BizException(SkillErrorCode.SOURCE_PACKAGE_MISSING);
        }
        Optional<SkillVersionEntity> draft = findDraftVersion(skillId);
        SkillVersionEntity target;
        int targetVersion;
        if (draft.isPresent()) {
            if (isEmptyDraft(draft.get())) {
                target = draft.get();
                targetVersion = target.getVersion();
                if (StringUtils.hasText(target.getStoragePath())) {
                    skillStorageService.deletePackage(skillId, targetVersion, target.getStoragePath());
                }
            } else {
                throw draftAlreadyExists();
            }
        } else {
            Optional<SkillVersionEntity> latest = versionRepository.findTopBySkillIdOrderByVersionDesc(skillId);
            targetVersion = latest.map(v -> v.getVersion() + 1).orElse(1);
            target = newVersionEntity(skillId, targetVersion);
        }
        String storagePath = skillStorageService.copyPackage(
                skillId, sourceVersion, source.getStoragePath(), targetVersion);
        if (!StringUtils.hasText(storagePath)) {
            throw new BizException(SkillErrorCode.PACKAGE_COPY_FAILED);
        }
        target.setSystemOverlay(source.getSystemOverlay());
        target.setToolsJson(source.getToolsJson());
        target.setMaxIters(source.getMaxIters());
        target.setSideEffect(source.getSideEffect());
        target.setSandbox(source.getSandbox());
        target.setReferencesJson(source.getReferencesJson());
        target.setScriptsJson(source.getScriptsJson());
        target.setStoragePath(storagePath);
        target.setStatus("draft");
        target.setMaintainer(resolveMaintainer(maintainer));
        target.setCreatedAt(Instant.now());
        versionRepository.save(target);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogRegistry.refresh();
        log.info("[SkillManager] forked skill={} from v{} to draft v{}", skillId, sourceVersion, targetVersion);
        return toCatalogEntry(def).orElseThrow();
    }

    @Transactional
    public SkillCatalogEntry setEnabled(String skillId, boolean enabled) {
        SkillDefinitionEntity def = requireDefinition(skillId);
        if (enabled) {
            requirePublishedActiveVersion(def);
        }
        def.setEnabled(enabled);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogRegistry.refresh();
        return toCatalogEntry(def).orElseThrow();
    }

    @Transactional
    public SkillCatalogEntry publish(String skillId, int version, String maintainer) {
        SkillDefinitionEntity def = requireDefinition(skillId);
        SkillVersionEntity ver = versionRepository.findBySkillIdAndVersion(skillId, version)
                .orElseThrow(() -> new BizException(SkillErrorCode.VERSION_NOT_FOUND));
        if (!StringUtils.hasText(ver.getStoragePath())) {
            throw new BizException(SkillErrorCode.PUBLISH_PACKAGE_REQUIRED);
        }
        ver.setStatus("published");
        ver.setMaintainer(resolveMaintainer(maintainer));
        versionRepository.save(ver);
        def.setActiveVersion(version);
        def.setEnabled(true);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogRegistry.refresh();
        return toCatalogEntry(def).orElseThrow();
    }

    @Transactional
    public void delete(String skillId) {
        SkillDefinitionEntity def = requireDefinition(skillId);
        List<SkillVersionEntity> versions = versionRepository.findBySkillIdOrderByVersionDesc(skillId);
        for (SkillVersionEntity ver : versions) {
            skillStorageService.deletePackage(skillId, ver.getVersion(), ver.getStoragePath());
        }
        versionRepository.deleteAll(versions);
        definitionRepository.delete(def);
        catalogRegistry.refresh();
        log.info("[SkillManager] deleted skill={}", skillId);
    }

    @Transactional
    public SkillCatalogEntry deleteVersion(String skillId, int version) {
        SkillDefinitionEntity def = requireDefinition(skillId);
        List<SkillVersionEntity> all = versionRepository.findBySkillIdOrderByVersionDesc(skillId);
        if (all.size() <= 1) {
            throw new BizException(SkillErrorCode.LAST_VERSION_DELETE_FORBIDDEN);
        }
        SkillVersionEntity ver = versionRepository.findBySkillIdAndVersion(skillId, version)
                .orElseThrow(() -> new BizException(SkillErrorCode.VERSION_NOT_FOUND));
        if (StringUtils.hasText(ver.getStoragePath())) {
            skillStorageService.deletePackage(skillId, ver.getVersion(), ver.getStoragePath());
        }
        versionRepository.delete(ver);
        if (def.getActiveVersion() == version) {
            reassignActiveVersion(def);
        }
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogRegistry.refresh();
        log.info("[SkillManager] deleted version skill={} version={}", skillId, version);
        return toCatalogEntry(def).orElseThrow();
    }

    private void reassignActiveVersion(SkillDefinitionEntity def) {
        List<SkillVersionEntity> remaining = versionRepository.findBySkillIdOrderByVersionDesc(def.getId());
        if (remaining.isEmpty()) {
            def.setEnabled(false);
            return;
        }
        Optional<SkillVersionEntity> next = remaining.stream()
                .filter(v -> "published".equals(v.getStatus()) && StringUtils.hasText(v.getStoragePath()))
                .findFirst();
        if (next.isEmpty()) {
            next = remaining.stream()
                    .filter(v -> StringUtils.hasText(v.getStoragePath()))
                    .findFirst();
        }
        SkillVersionEntity target = next.orElse(remaining.get(0));
        def.setActiveVersion(target.getVersion());
        if (!"published".equals(target.getStatus()) || !StringUtils.hasText(target.getStoragePath())) {
            def.setEnabled(false);
        }
    }

    public List<SkillVersionEntity> listVersions(String skillId) {
        requireDefinition(skillId);
        return versionRepository.findBySkillIdOrderByVersionDesc(skillId);
    }

    private static boolean isEmptyDraft(SkillVersionEntity ver) {
        return "draft".equals(ver.getStatus()) && !StringUtils.hasText(ver.getStoragePath());
    }

    private Optional<SkillVersionEntity> findDraftVersion(String skillId) {
        return versionRepository.findFirstBySkillIdAndStatusOrderByVersionDesc(skillId, "draft");
    }

    private static BizException draftAlreadyExists() {
        return new BizException(SkillErrorCode.DRAFT_ALREADY_EXISTS);
    }

    private static SkillVersionEntity newVersionEntity(String skillId, int targetVersion) {
        SkillVersionEntity version = new SkillVersionEntity();
        version.setSkillId(skillId);
        version.setVersion(targetVersion);
        version.setToolsJson("[]");
        version.setMaxIters(4);
        version.setSideEffect("read");
        version.setSandbox("none");
        return version;
    }

    private static String resolveMaintainer(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        String value = userId.strip();
        if ("unknown".equalsIgnoreCase(value)) {
            return null;
        }
        return value;
    }

    private static void validateSkillName(String skillId, String name) {
        if (StringUtils.hasText(name) && !skillId.equals(name.strip())) {
            throw new BizException(SkillErrorCode.SKILL_NAME_MISMATCH);
        }
    }

    private static void applyDefinitionMeta(SkillDefinitionEntity def, SkillMdDocument doc) {
        if (StringUtils.hasText(doc.description())) {
            def.setDescription(doc.description());
        }
        if (!StringUtils.hasText(def.getDisplayName())) {
            def.setDisplayName(StringUtils.hasText(doc.name()) ? doc.name() : def.getId());
        }
    }

    private SkillDefinitionEntity requireDefinition(String skillId) {
        return definitionRepository.findById(skillId)
                .orElseThrow(() -> new BizException(SkillErrorCode.SKILL_NOT_FOUND));
    }

    private void requirePublishedActiveVersion(SkillDefinitionEntity def) {
        SkillVersionEntity ver = versionRepository.findBySkillIdAndVersion(def.getId(), def.getActiveVersion())
                .orElseThrow(() -> new BizException(SkillErrorCode.ENABLE_REQUIRES_PUBLISHED));
        if (!"published".equals(ver.getStatus()) || !StringUtils.hasText(ver.getStoragePath())) {
            throw new BizException(SkillErrorCode.ENABLE_REQUIRES_PUBLISHED);
        }
    }

    private Optional<SkillCatalogEntry> toCatalogEntry(SkillDefinitionEntity def) {
        return versionRepository.findBySkillIdAndVersion(def.getId(), def.getActiveVersion())
                .map(ver -> new SkillCatalogEntry(
                        def.getId(),
                        def.getDisplayName(),
                        def.getDescription(),
                        ver.getSystemOverlay(),
                        ver.getVersion(),
                        def.isEnabled(),
                        ver.getCreatedAt(),
                        ver.getMaintainer(),
                        isPublishedVersion(ver)));
    }

    private static boolean isPublishedVersion(SkillVersionEntity ver) {
        return "published".equals(ver.getStatus()) && StringUtils.hasText(ver.getStoragePath());
    }

    static List<String> listPrefixedPaths(Map<String, byte[]> files, String prefix) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (String path : files.keySet()) {
            if (path.startsWith(prefix) && !path.endsWith("/")) {
                paths.add(path);
            }
        }
        paths.sort(String::compareTo);
        return List.copyOf(paths);
    }

    static String writePathList(List<String> paths) {
        try {
            return MAPPER.writeValueAsString(paths != null ? paths : List.of());
        } catch (IOException e) {
            return "[]";
        }
    }
}
