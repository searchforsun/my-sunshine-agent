package com.sunshine.workflow.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.workflow.dto.WorkflowListItem;
import com.sunshine.workflow.entity.WorkflowDefinitionEntity;
import com.sunshine.workflow.entity.WorkflowDefinitionId;
import com.sunshine.workflow.entity.WorkflowVersionEntity;
import com.sunshine.workflow.exception.WorkflowErrorCode;
import com.sunshine.workflow.repo.WorkflowDefinitionRepository;
import com.sunshine.workflow.repo.WorkflowVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 导入 / 导出 / fork 草稿 — 从 WorkflowAdminService 拆出 */
@Service
@RequiredArgsConstructor
public class WorkflowPackageService {

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowAdminSupport support;

    @Transactional
    public WorkflowListItem forkVersion(String workflowId, String tenantId, int sourceVersion) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
        WorkflowVersionEntity source = versionRepository
                .findByTenantIdAndWorkflowIdAndVersion(def.tenantId(), def.workflowId(), sourceVersion)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
        if (support.findDraftVersion(def).isPresent()) {
            throw new BizException(WorkflowErrorCode.DRAFT_ALREADY_EXISTS);
        }
        int targetVersion = support.nextVersionNumber(def);
        WorkflowVersionEntity target = support.newDraftVersion(
                def.tenantId(),
                def.workflowId(),
                targetVersion,
                support.readMap(source.getPlanJson()),
                support.readMap(source.getCatalogMeta()));
        versionRepository.save(target);
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        return support.toListItem(def);
    }

    @Transactional
    public WorkflowListItem importPackage(String tenantId, Map<String, Object> body) {
        String workflowId = WorkflowAdminSupport.stringVal(body.get("workflowId"));
        if (!StringUtils.hasText(workflowId)) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
        WorkflowAdminSupport.requireDescription(WorkflowAdminSupport.stringVal(body.get("description")));
        String tenant = support.tenantOrDefault(tenantId);
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
        def.setDisplayName(WorkflowAdminSupport.stringVal(body.get("displayName"), workflowId));
        def.setDescription(WorkflowAdminSupport.stringVal(body.get("description")).strip());
        def.setKind(WorkflowAdminSupport.normalizeKind(WorkflowAdminSupport.stringVal(body.get("kind"))));
        def.setUpdatedAt(Instant.now());
        definitionRepository.save(def);
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) body.get("plan");
        Map<String, Object> catalog = body.get("catalog") instanceof Map<?, ?> m
                ? WorkflowAdminSupport.castMap(m) : Map.of();
        if (plan == null || plan.isEmpty()) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
        WorkflowVersionEntity draft = support.findDraftVersion(def).orElseGet(() -> {
            int version = support.nextVersionNumber(def);
            return support.newDraftVersion(tenant, workflowId.strip(), version, plan, catalog);
        });
        draft.setPlanJson(support.writeJson(plan));
        draft.setCatalogMeta(support.writeJson(catalog));
        draft.setCreatedAt(support.uniqueCreatedAt(def.tenantId(), def.workflowId(), draft.getId()));
        versionRepository.save(draft);
        return support.toListItem(def);
    }

    public Map<String, Object> exportPackage(String workflowId, String tenantId, int version) {
        WorkflowDefinitionEntity def = support.requireDefinition(workflowId, tenantId);
        WorkflowVersionEntity ver = versionRepository
                .findByTenantIdAndWorkflowIdAndVersion(def.tenantId(), def.workflowId(), version)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowId", def.workflowId());
        body.put("displayName", def.getDisplayName());
        body.put("description", def.getDescription());
        body.put("kind", WorkflowAdminSupport.normalizeKind(def.getKind()));
        body.put("version", ver.getVersion());
        body.put("status", ver.getStatus());
        body.put("plan", support.readMap(ver.getPlanJson()));
        body.put("catalog", support.readMap(ver.getCatalogMeta()));
        return body;
    }
}
