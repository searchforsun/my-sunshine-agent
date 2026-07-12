package com.sunshine.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.FixedErrorCode;
import com.sunshine.common.util.VersionTimestampDedup;
import com.sunshine.workflow.dto.WorkflowCatalogEntry;
import com.sunshine.workflow.dto.WorkflowCreateRequest;
import com.sunshine.workflow.dto.WorkflowDraftRequest;
import com.sunshine.workflow.dto.WorkflowEditableResponse;
import com.sunshine.workflow.dto.WorkflowEnableRequest;
import com.sunshine.workflow.dto.WorkflowListItem;
import com.sunshine.workflow.dto.WorkflowPlanValidateResponse;
import com.sunshine.workflow.dto.WorkflowPublishedResponse;
import com.sunshine.workflow.dto.WorkflowUpdateRequest;
import com.sunshine.workflow.dto.WorkflowVersionItem;
import com.sunshine.workflow.entity.WorkflowDefinitionEntity;
import com.sunshine.workflow.entity.WorkflowDefinitionId;
import com.sunshine.workflow.entity.WorkflowVersionEntity;
import com.sunshine.workflow.event.WorkflowCatalogChangePublisher;
import com.sunshine.workflow.exception.WorkflowErrorCode;
import com.sunshine.workflow.repo.WorkflowDefinitionRepository;
import com.sunshine.workflow.repo.WorkflowVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkflowAdminService {

    private static final String DEFAULT_TENANT = "default";

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowPlanValidator planValidator;
    private final WorkflowCatalogChangePublisher catalogChangePublisher;
    private final ObjectMapper objectMapper;

    public List<WorkflowCatalogEntry> listCatalog(String tenantId) {
        String tenant = tenantOrDefault(tenantId);
        return definitionRepository.findByPkTenantIdOrderByCreatedAtAscPkIdAsc(tenant).stream()
                .filter(WorkflowDefinitionEntity::isEnabled)
                .filter(def -> def.getActiveVersion() > 0)
                .map(this::toCatalogEntry)
                .toList();
    }

    public List<WorkflowListItem> listWorkflows(String tenantId) {
        String tenant = tenantOrDefault(tenantId);
        return definitionRepository.findByPkTenantIdOrderByCreatedAtAscPkIdAsc(tenant).stream()
                .map(this::toListItem)
                .toList();
    }

    public WorkflowPublishedResponse getPublished(String workflowId, String tenantId) {
        WorkflowDefinitionEntity def = requireEnabledDefinition(workflowId, tenantId);
        WorkflowVersionEntity version = requirePublishedVersion(def);
        return toPublishedResponse(def.workflowId(), version);
    }

    /** Studio 编辑：优先最新 draft，否则回落当前 published */
    public WorkflowEditableResponse getEditable(String workflowId, String tenantId) {
        return getVersion(workflowId, tenantId, null);
    }

    public WorkflowEditableResponse getVersion(String workflowId, String tenantId, Integer version) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        if (version != null && version > 0) {
            WorkflowVersionEntity ver = versionRepository
                    .findByTenantIdAndWorkflowIdAndVersion(def.tenantId(), def.workflowId(), version)
                    .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
            return toEditableResponse(def.workflowId(), ver);
        }
        Optional<WorkflowVersionEntity> draft = findDraftVersion(def);
        if (draft.isPresent()) {
            return toEditableResponse(def.workflowId(), draft.get());
        }
        if (def.isEnabled() && def.getActiveVersion() > 0) {
            return toEditableResponse(def.workflowId(), requirePublishedVersion(def));
        }
        throw new BizException(WorkflowErrorCode.DRAFT_MISSING);
    }

    public List<WorkflowVersionItem> listVersions(String workflowId, String tenantId) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        return versionRepository.findByTenantIdAndWorkflowIdOrderByVersionDesc(def.tenantId(), def.workflowId())
                .stream()
                .map(this::toVersionItem)
                .toList();
    }

    public WorkflowPlanValidateResponse validatePlan(Map<String, Object> plan) {
        WorkflowPlanValidationResult result = planValidator.validateDetailed(plan);
        return new WorkflowPlanValidateResponse(result.isValid(), result.issues());
    }

    @Transactional
    public WorkflowListItem create(String tenantId, WorkflowCreateRequest request) {
        if (!StringUtils.hasText(request.id()) || !StringUtils.hasText(request.displayName())) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
        requireDescription(request.description());
        String tenant = tenantOrDefault(tenantId);
        WorkflowDefinitionId pk = new WorkflowDefinitionId(tenant, request.id().strip());
        if (definitionRepository.existsById(pk)) {
            throw new BizException(WorkflowErrorCode.WORKFLOW_EXISTS);
        }
        WorkflowDefinitionEntity def = new WorkflowDefinitionEntity();
        def.setPk(pk);
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description().strip());
        def.setEnabled(false);
        def.setActiveVersion(0);
        def.setSource("studio");
        def.setCreatedAt(Instant.now());
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        WorkflowVersionEntity draft = newDraftVersion(
                tenant, def.workflowId(), 1, emptyPlan(def.workflowId()), Map.of());
        versionRepository.save(draft);
        return toListItem(def);
    }

    @Transactional
    public WorkflowListItem updateMeta(String workflowId, String tenantId, WorkflowUpdateRequest request) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
        requireDescription(request.description());
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description().strip());
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        if (def.isEnabled() && def.getActiveVersion() > 0) {
            catalogChangePublisher.publish(def.tenantId());
        }
        return toListItem(def);
    }

    @Transactional
    public WorkflowListItem setEnabled(String workflowId, String tenantId, WorkflowEnableRequest request) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        if (request.enabled()) {
            if (def.getActiveVersion() <= 0) {
                throw new BizException(WorkflowErrorCode.ENABLE_REQUIRES_PUBLISHED);
            }
            WorkflowVersionEntity active = versionRepository
                    .findByTenantIdAndWorkflowIdAndVersion(def.tenantId(), def.workflowId(), def.getActiveVersion())
                    .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
            if (!"published".equals(active.getStatus())) {
                throw new BizException(WorkflowErrorCode.ENABLE_REQUIRES_PUBLISHED);
            }
        }
        def.setEnabled(request.enabled());
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogChangePublisher.publish(def.tenantId());
        return toListItem(def);
    }

    @Transactional
    public void saveDraft(String workflowId, String tenantId, WorkflowDraftRequest request) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        if (request.plan() == null || request.plan().isEmpty()) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
        WorkflowVersionEntity draft = findDraftVersion(def).orElseGet(() -> {
            int nextVersion = nextVersionNumber(def);
            return newDraftVersion(def.tenantId(), def.workflowId(), nextVersion, request.plan(), request.catalog());
        });
        draft.setPlanJson(writeJson(request.plan()));
        draft.setCatalogMeta(writeJson(request.catalog()));
        draft.setCreatedAt(uniqueCreatedAt(def.tenantId(), def.workflowId(), draft.getId()));
        versionRepository.save(draft);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
    }

    @Transactional
    public WorkflowPublishedResponse publish(String workflowId, String tenantId, Integer version) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        int targetVersion = version != null && version > 0 ? version : latestDraftVersion(def);
        WorkflowVersionEntity target = versionRepository
                .findByTenantIdAndWorkflowIdAndVersion(def.tenantId(), def.workflowId(), targetVersion)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
        if ("published".equals(target.getStatus())) {
            def.setEnabled(true);
            def.setActiveVersion(targetVersion);
            def.setUpdatedAt(Instant.now());
            definitionRepository.save(def);
            catalogChangePublisher.publish(def.tenantId());
            return toPublishedResponse(def.workflowId(), target);
        }
        Map<String, Object> plan = readMap(target.getPlanJson());
        requireValidPlan(plan);
        target.setStatus("published");
        target.setPublishedAt(Instant.now());
        target.setCreatedAt(uniqueCreatedAt(def.tenantId(), def.workflowId(), target.getId()));
        versionRepository.save(target);
        def.setEnabled(true);
        def.setActiveVersion(targetVersion);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogChangePublisher.publish(def.tenantId());
        return toPublishedResponse(def.workflowId(), target);
    }

    @Transactional
    public WorkflowListItem forkVersion(String workflowId, String tenantId, int sourceVersion) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        WorkflowVersionEntity source = versionRepository
                .findByTenantIdAndWorkflowIdAndVersion(def.tenantId(), def.workflowId(), sourceVersion)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
        Optional<WorkflowVersionEntity> draft = findDraftVersion(def);
        WorkflowVersionEntity target;
        if (draft.isPresent()) {
            throw new BizException(WorkflowErrorCode.DRAFT_ALREADY_EXISTS);
        }
        int targetVersion = nextVersionNumber(def);
        target = newDraftVersion(
                def.tenantId(),
                def.workflowId(),
                targetVersion,
                readMap(source.getPlanJson()),
                readMap(source.getCatalogMeta()));
        versionRepository.save(target);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        return toListItem(def);
    }

    @Transactional
    public WorkflowListItem importPackage(String tenantId, Map<String, Object> body) {
        String workflowId = stringVal(body.get("workflowId"));
        if (!StringUtils.hasText(workflowId)) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
        requireDescription(stringVal(body.get("description")));
        String tenant = tenantOrDefault(tenantId);
        WorkflowDefinitionId pk = new WorkflowDefinitionId(tenant, workflowId.strip());
        WorkflowDefinitionEntity def = definitionRepository.findById(pk).orElseGet(() -> {
            WorkflowDefinitionEntity created = new WorkflowDefinitionEntity();
            created.setPk(pk);
            created.setMode("workflow");
            created.setSource("import");
            created.setEnabled(false);
            created.setActiveVersion(0);
            created.setCreatedAt(Instant.now());
            return created;
        });
        def.setDisplayName(stringVal(body.get("displayName"), workflowId));
        def.setDescription(stringVal(body.get("description")).strip());
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) body.get("plan");
        @SuppressWarnings("unchecked")
        Map<String, Object> catalog = body.get("catalog") instanceof Map<?, ?> m
                ? castMap(m) : Map.of();
        if (plan == null || plan.isEmpty()) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
        WorkflowVersionEntity draft = findDraftVersion(def).orElseGet(() -> {
            int version = nextVersionNumber(def);
            return newDraftVersion(tenant, workflowId.strip(), version, plan, catalog);
        });
        draft.setPlanJson(writeJson(plan));
        draft.setCatalogMeta(writeJson(catalog));
        draft.setCreatedAt(uniqueCreatedAt(def.tenantId(), def.workflowId(), draft.getId()));
        versionRepository.save(draft);
        return toListItem(def);
    }

    public Map<String, Object> exportPackage(String workflowId, String tenantId, int version) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        WorkflowVersionEntity ver = versionRepository
                .findByTenantIdAndWorkflowIdAndVersion(def.tenantId(), def.workflowId(), version)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowId", def.workflowId());
        body.put("displayName", def.getDisplayName());
        body.put("description", def.getDescription());
        body.put("version", ver.getVersion());
        body.put("status", ver.getStatus());
        body.put("plan", readMap(ver.getPlanJson()));
        body.put("catalog", readMap(ver.getCatalogMeta()));
        return body;
    }

    @Transactional
    public void delete(String workflowId, String tenantId) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        List<WorkflowVersionEntity> versions = versionRepository
                .findByTenantIdAndWorkflowIdOrderByVersionDesc(def.tenantId(), def.workflowId());
        versionRepository.deleteAll(versions);
        definitionRepository.delete(def);
        catalogChangePublisher.publish(def.tenantId());
    }

    @Transactional
    public WorkflowListItem deleteVersion(String workflowId, String tenantId, int version) {
        WorkflowDefinitionEntity def = requireDefinition(workflowId, tenantId);
        List<WorkflowVersionEntity> all = versionRepository
                .findByTenantIdAndWorkflowIdOrderByVersionDesc(def.tenantId(), def.workflowId());
        if (all.size() <= 1) {
            throw new BizException(WorkflowErrorCode.LAST_VERSION_DELETE_FORBIDDEN);
        }
        WorkflowVersionEntity ver = versionRepository
                .findByTenantIdAndWorkflowIdAndVersion(def.tenantId(), def.workflowId(), version)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
        versionRepository.delete(ver);
        if (def.getActiveVersion() == version) {
            reassignActiveVersion(def);
        }
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogChangePublisher.publish(def.tenantId());
        return toListItem(def);
    }

    public boolean isKnownEnabled(String workflowId, String tenantId) {
        return findEnabledDefinition(workflowId, tenantId).isPresent();
    }

    private Optional<WorkflowDefinitionEntity> findEnabledDefinition(String workflowId, String tenantId) {
        if (!StringUtils.hasText(workflowId)) {
            return Optional.empty();
        }
        WorkflowDefinitionId pk = new WorkflowDefinitionId(tenantOrDefault(tenantId), workflowId.strip());
        return definitionRepository.findById(pk)
                .filter(def -> def.isEnabled() && def.getActiveVersion() > 0);
    }

    private WorkflowDefinitionEntity requireDefinition(String workflowId, String tenantId) {
        WorkflowDefinitionId pk = new WorkflowDefinitionId(tenantOrDefault(tenantId), workflowId.strip());
        return definitionRepository.findById(pk)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.WORKFLOW_NOT_FOUND));
    }

    private WorkflowDefinitionEntity requireEnabledDefinition(String workflowId, String tenantId) {
        return findEnabledDefinition(workflowId, tenantId)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.WORKFLOW_NOT_FOUND));
    }

    private WorkflowVersionEntity requirePublishedVersion(WorkflowDefinitionEntity def) {
        return versionRepository.findByTenantIdAndWorkflowIdAndVersion(
                        def.tenantId(), def.workflowId(), def.getActiveVersion())
                .filter(v -> "published".equals(v.getStatus()))
                .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
    }

    private Optional<WorkflowVersionEntity> findDraftVersion(WorkflowDefinitionEntity def) {
        return versionRepository.findFirstByTenantIdAndWorkflowIdAndStatusOrderByVersionDesc(
                def.tenantId(), def.workflowId(), "draft");
    }

    private int nextVersionNumber(WorkflowDefinitionEntity def) {
        return versionRepository.findByTenantIdAndWorkflowIdOrderByVersionDesc(
                        def.tenantId(), def.workflowId()).stream()
                .mapToInt(WorkflowVersionEntity::getVersion)
                .max()
                .orElse(0) + 1;
    }

    private int latestDraftVersion(WorkflowDefinitionEntity def) {
        return findDraftVersion(def)
                .map(WorkflowVersionEntity::getVersion)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.DRAFT_MISSING));
    }

    private void reassignActiveVersion(WorkflowDefinitionEntity def) {
        List<WorkflowVersionEntity> remaining = versionRepository
                .findByTenantIdAndWorkflowIdOrderByVersionDesc(def.tenantId(), def.workflowId());
        if (remaining.isEmpty()) {
            def.setEnabled(false);
            def.setActiveVersion(0);
            return;
        }
        Optional<WorkflowVersionEntity> next = remaining.stream()
                .filter(v -> "published".equals(v.getStatus()))
                .findFirst();
        if (next.isEmpty()) {
            def.setEnabled(false);
            def.setActiveVersion(0);
            return;
        }
        def.setActiveVersion(next.get().getVersion());
    }

    private WorkflowListItem toListItem(WorkflowDefinitionEntity def) {
        Instant activeCreatedAt = null;
        boolean activePublished = false;
        if (def.getActiveVersion() > 0) {
            Optional<WorkflowVersionEntity> active = versionRepository.findByTenantIdAndWorkflowIdAndVersion(
                    def.tenantId(), def.workflowId(), def.getActiveVersion());
            if (active.isPresent()) {
                activeCreatedAt = active.get().getCreatedAt();
                activePublished = "published".equals(active.get().getStatus());
            }
        }
        return new WorkflowListItem(
                def.workflowId(),
                def.getDisplayName(),
                def.getDescription(),
                def.isEnabled(),
                def.getActiveVersion(),
                def.getSource(),
                def.getUpdatedAt(),
                activeCreatedAt,
                activePublished);
    }

    private WorkflowVersionItem toVersionItem(WorkflowVersionEntity version) {
        return new WorkflowVersionItem(
                version.getId(),
                version.getWorkflowId(),
                version.getVersion(),
                version.getStatus(),
                version.getCreatedAt(),
                version.getPublishedAt());
    }

    private WorkflowCatalogEntry toCatalogEntry(WorkflowDefinitionEntity def) {
        WorkflowVersionEntity version = requirePublishedVersion(def);
        Map<String, Object> meta = readMap(version.getCatalogMeta());
        return new WorkflowCatalogEntry(
                def.workflowId(),
                def.getMode(),
                def.getDisplayName(),
                def.getDescription(),
                stringList(meta.get("examples")),
                stringList(meta.get("nodeSummary")),
                textOrNull(meta.get("intentAfter")));
    }

    private WorkflowPublishedResponse toPublishedResponse(String workflowId, WorkflowVersionEntity version) {
        return new WorkflowPublishedResponse(
                workflowId,
                version.getVersion(),
                readMap(version.getPlanJson()),
                readMap(version.getCatalogMeta()));
    }

    private WorkflowEditableResponse toEditableResponse(String workflowId, WorkflowVersionEntity version) {
        return new WorkflowEditableResponse(
                workflowId,
                version.getVersion(),
                version.getStatus(),
                readMap(version.getPlanJson()),
                readMap(version.getCatalogMeta()));
    }

    private WorkflowVersionEntity newDraftVersion(
            String tenantId,
            String workflowId,
            int version,
            Map<String, Object> plan,
            Map<String, Object> catalog) {
        WorkflowVersionEntity entity = new WorkflowVersionEntity();
        entity.setTenantId(tenantId);
        entity.setWorkflowId(workflowId);
        entity.setVersion(version);
        entity.setStatus("draft");
        entity.setPlanJson(writeJson(plan));
        entity.setCatalogMeta(writeJson(catalog));
        entity.setCreatedAt(uniqueCreatedAt(tenantId, workflowId, null));
        return entity;
    }

    private Instant uniqueCreatedAt(String tenantId, String workflowId, Long excludeVersionRowId) {
        List<Instant> existing = versionRepository
                .findByTenantIdAndWorkflowIdOrderByVersionDesc(tenantId, workflowId).stream()
                .filter(v -> excludeVersionRowId == null || !Objects.equals(v.getId(), excludeVersionRowId))
                .map(WorkflowVersionEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .toList();
        return VersionTimestampDedup.uniqueInstant(Instant.now(), existing);
    }

    private Map<String, Object> emptyPlan(String workflowId) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planId", null);
        plan.put("reason", "新建工作流 " + workflowId);
        plan.put("nodes", List.of(
                Map.of("id", "start", "type", "start", "displayName", "开始", "params", Map.of()),
                Map.of(
                        "id", "answer",
                        "type", "answer",
                        "displayName", "生成回答",
                        "params", Map.of(
                                "prompt", "请根据上游数据回答用户问题。\n\n{{plan.upstream}}",
                                "retry.maxAttempts", "2",
                                "retry.backoffMs", "500",
                                "retry.onFailure", "fail_fast"))));
        plan.put("edges", List.of(
                Map.of("from", "start", "to", "answer")));
        return plan;
    }

    private static void requireDescription(String description) {
        if (!StringUtils.hasText(description)) {
            throw new BizException(WorkflowErrorCode.DESCRIPTION_REQUIRED);
        }
    }

    private void requireValidPlan(Map<String, Object> plan) {
        WorkflowPlanValidationResult result = planValidator.validateDetailed(plan);
        if (!result.isValid()) {
            throw new BizException(new FixedErrorCode(
                    WorkflowErrorCode.PLAN_INVALID.getCode(),
                    WorkflowErrorCode.PLAN_INVALID.getKey(),
                    result.joinedIssues()));
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
    }

    private static String tenantOrDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.strip() : DEFAULT_TENANT;
    }

    private static String stringVal(Object raw) {
        return stringVal(raw, null);
    }

    private static String stringVal(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String text = raw.toString().strip();
        return text.isEmpty() ? fallback : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(item.toString());
            }
        }
        return List.copyOf(out);
    }

    private static String textOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().strip();
        return text.isEmpty() ? null : text;
    }
}
