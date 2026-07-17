package com.sunshine.workflow.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.FixedErrorCode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Workflow CRUD / 发布 / 校验 — 导入导出见 {@link WorkflowPackageService} */
@Service
@RequiredArgsConstructor
public class WorkflowAdminService {

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowPlanValidator planValidator;
    private final WorkflowCatalogChangePublisher catalogChangePublisher;
    private final WorkflowAdminSupport support;

    public List<WorkflowCatalogEntry> listCatalog(String tenantId) {
        String tenant = support.tenantOrDefault(tenantId);
        return definitionRepository.findByPkTenantIdOrderByCreatedAtAscPkIdAsc(tenant).stream()
                .filter(WorkflowDefinitionEntity::isEnabled)
                .filter(def -> def.getActiveVersion() > 0)
                .map(this::toCatalogEntry)
                .toList();
    }

    public List<WorkflowListItem> listWorkflows(String tenantId) {
        String tenant = support.tenantOrDefault(tenantId);
        return definitionRepository.findByPkTenantIdOrderByCreatedAtAscPkIdAsc(tenant).stream()
                .map(support::toListItem)
                .toList();
    }

    public WorkflowPublishedResponse getPublished(String workflowId, String tenantId) {
        WorkflowDefinitionEntity def = support.requireEnabledDefinition(workflowId, tenantId);
        WorkflowVersionEntity version = support.requirePublishedVersion(def);
        return toPublishedResponse(def.workflowId(), version);
    }

    /** Studio 编辑：优先最新 draft，否则回落当前 published */
    public WorkflowEditableResponse getEditable(String workflowId, String tenantId) {
        return getVersion(workflowId, tenantId, null);
    }

    public WorkflowEditableResponse getVersion(String workflowId, String tenantId, Integer version) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
        if (version != null && version > 0) {
            WorkflowVersionEntity ver = versionRepository
                    .findByTenantIdAndWorkflowIdAndVersion(def.tenantId(), def.workflowId(), version)
                    .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
            return toEditableResponse(def.workflowId(), ver);
        }
        Optional<WorkflowVersionEntity> draft = support.findDraftVersion(def);
        if (draft.isPresent()) {
            return toEditableResponse(def.workflowId(), draft.get());
        }
        if (def.isEnabled() && def.getActiveVersion() > 0) {
            return toEditableResponse(def.workflowId(), support.requirePublishedVersion(def));
        }
        throw new BizException(WorkflowErrorCode.DRAFT_MISSING);
    }

    public List<WorkflowVersionItem> listVersions(String workflowId, String tenantId) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
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
        WorkflowAdminSupport.requireDescription(request.description());
        String tenant = support.tenantOrDefault(tenantId);
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
        WorkflowVersionEntity draft = support.newDraftVersion(
                tenant, def.workflowId(), 1, emptyPlan(def.workflowId()), Map.of());
        versionRepository.save(draft);
        return support.toListItem(def);
    }

    @Transactional
    public WorkflowListItem updateMeta(String workflowId, String tenantId, WorkflowUpdateRequest request) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
        if (!StringUtils.hasText(request.displayName())) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
        WorkflowAdminSupport.requireDescription(request.description());
        def.setDisplayName(request.displayName().strip());
        def.setDescription(request.description().strip());
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        if (def.isEnabled() && def.getActiveVersion() > 0) {
            catalogChangePublisher.publish(def.tenantId());
        }
        return support.toListItem(def);
    }

    @Transactional
    public WorkflowListItem setEnabled(String workflowId, String tenantId, WorkflowEnableRequest request) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
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
        return support.toListItem(def);
    }

    @Transactional
    public void saveDraft(String workflowId, String tenantId, WorkflowDraftRequest request) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
        if (request.plan() == null || request.plan().isEmpty()) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
        WorkflowVersionEntity draft = support.findDraftVersion(def).orElseGet(() -> {
            int nextVersion = support.nextVersionNumber(def);
            return support.newDraftVersion(
                    def.tenantId(), def.workflowId(), nextVersion, request.plan(), request.catalog());
        });
        draft.setPlanJson(support.writeJson(request.plan()));
        draft.setCatalogMeta(support.writeJson(request.catalog()));
        draft.setCreatedAt(support.uniqueCreatedAt(def.tenantId(), def.workflowId(), draft.getId()));
        versionRepository.save(draft);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
    }

    @Transactional
    public WorkflowPublishedResponse publish(String workflowId, String tenantId, Integer version) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
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
        Map<String, Object> plan = support.readMap(target.getPlanJson());
        requireValidPlan(plan);
        target.setStatus("published");
        target.setPublishedAt(Instant.now());
        target.setCreatedAt(support.uniqueCreatedAt(def.tenantId(), def.workflowId(), target.getId()));
        versionRepository.save(target);
        def.setEnabled(true);
        def.setActiveVersion(targetVersion);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        catalogChangePublisher.publish(def.tenantId());
        return toPublishedResponse(def.workflowId(), target);
    }

    @Transactional
    public void delete(String workflowId, String tenantId) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
        List<WorkflowVersionEntity> versions = versionRepository
                .findByTenantIdAndWorkflowIdOrderByVersionDesc(def.tenantId(), def.workflowId());
        versionRepository.deleteAll(versions);
        definitionRepository.delete(def);
        catalogChangePublisher.publish(def.tenantId());
    }

    @Transactional
    public WorkflowListItem deleteVersion(String workflowId, String tenantId, int version) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
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
        return support.toListItem(def);
    }

    public boolean isKnownEnabled(String workflowId, String tenantId) {
        return support.findEnabledDefinition(workflowId, tenantId).isPresent();
    }

    private int latestDraftVersion(WorkflowDefinitionEntity def) {
        return support.findDraftVersion(def)
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
        WorkflowVersionEntity version = support.requirePublishedVersion(def);
        Map<String, Object> meta = support.readMap(version.getCatalogMeta());
        return new WorkflowCatalogEntry(
                def.workflowId(),
                def.getMode(),
                def.getDisplayName(),
                def.getDescription(),
                WorkflowAdminSupport.stringList(meta.get("examples")),
                WorkflowAdminSupport.stringList(meta.get("nodeSummary")),
                WorkflowAdminSupport.textOrNull(meta.get("intentAfter")));
    }

    private WorkflowPublishedResponse toPublishedResponse(String workflowId, WorkflowVersionEntity version) {
        return new WorkflowPublishedResponse(
                workflowId,
                version.getVersion(),
                support.readMap(version.getPlanJson()),
                support.readMap(version.getCatalogMeta()));
    }

    private WorkflowEditableResponse toEditableResponse(String workflowId, WorkflowVersionEntity version) {
        return new WorkflowEditableResponse(
                workflowId,
                version.getVersion(),
                version.getStatus(),
                support.readMap(version.getPlanJson()),
                support.readMap(version.getCatalogMeta()));
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

    private void requireValidPlan(Map<String, Object> plan) {
        WorkflowPlanValidationResult result = planValidator.validateDetailed(plan);
        if (!result.isValid()) {
            throw new BizException(new FixedErrorCode(
                    WorkflowErrorCode.PLAN_INVALID.getCode(),
                    WorkflowErrorCode.PLAN_INVALID.getKey(),
                    result.joinedIssues()));
        }
    }
}
