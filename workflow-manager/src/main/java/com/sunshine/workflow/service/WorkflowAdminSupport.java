package com.sunshine.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.util.VersionTimestampDedup;
import com.sunshine.workflow.dto.WorkflowListItem;
import com.sunshine.workflow.entity.WorkflowDefinitionEntity;
import com.sunshine.workflow.entity.WorkflowDefinitionId;
import com.sunshine.workflow.entity.WorkflowVersionEntity;
import com.sunshine.workflow.exception.WorkflowErrorCode;
import com.sunshine.workflow.repo.WorkflowDefinitionRepository;
import com.sunshine.workflow.repo.WorkflowVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Admin / Package 共用：租户、定义查找、草稿版本与 JSON 编解码 */
@Component
@RequiredArgsConstructor
public class WorkflowAdminSupport {

    static final String DEFAULT_TENANT = "default";

    private final WorkflowDefinitionRepository definitionRepository;
    private final WorkflowVersionRepository versionRepository;
    private final ObjectMapper objectMapper;

    public String tenantOrDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.strip() : DEFAULT_TENANT;
    }

    public WorkflowDefinitionEntity requireDefinition(String workflowId, String tenantId) {
        WorkflowDefinitionId pk = new WorkflowDefinitionId(tenantOrDefault(tenantId), workflowId.strip());
        return definitionRepository.findById(pk)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.WORKFLOW_NOT_FOUND));
    }

    public Optional<WorkflowDefinitionEntity> findEnabledDefinition(String workflowId, String tenantId) {
        if (!StringUtils.hasText(workflowId)) {
            return Optional.empty();
        }
        WorkflowDefinitionId pk = new WorkflowDefinitionId(tenantOrDefault(tenantId), workflowId.strip());
        return definitionRepository.findById(pk)
                .filter(def -> def.isEnabled() && def.getActiveVersion() > 0);
    }

    public WorkflowDefinitionEntity requireEnabledDefinition(String workflowId, String tenantId) {
        return findEnabledDefinition(workflowId, tenantId)
                .orElseThrow(() -> new BizException(WorkflowErrorCode.WORKFLOW_NOT_FOUND));
    }

    public WorkflowVersionEntity requirePublishedVersion(WorkflowDefinitionEntity def) {
        return versionRepository.findByTenantIdAndWorkflowIdAndVersion(
                        def.tenantId(), def.workflowId(), def.getActiveVersion())
                .filter(v -> "published".equals(v.getStatus()))
                .orElseThrow(() -> new BizException(WorkflowErrorCode.VERSION_NOT_FOUND));
    }

    public Optional<WorkflowVersionEntity> findDraftVersion(WorkflowDefinitionEntity def) {
        return versionRepository.findFirstByTenantIdAndWorkflowIdAndStatusOrderByVersionDesc(
                def.tenantId(), def.workflowId(), "draft");
    }

    public int nextVersionNumber(WorkflowDefinitionEntity def) {
        return versionRepository.findByTenantIdAndWorkflowIdOrderByVersionDesc(
                        def.tenantId(), def.workflowId()).stream()
                .mapToInt(WorkflowVersionEntity::getVersion)
                .max()
                .orElse(0) + 1;
    }

    public WorkflowVersionEntity newDraftVersion(
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

    public Instant uniqueCreatedAt(String tenantId, String workflowId, Long excludeVersionRowId) {
        List<Instant> existing = versionRepository
                .findByTenantIdAndWorkflowIdOrderByVersionDesc(tenantId, workflowId).stream()
                .filter(v -> excludeVersionRowId == null || !Objects.equals(v.getId(), excludeVersionRowId))
                .map(WorkflowVersionEntity::getCreatedAt)
                .filter(Objects::nonNull)
                .toList();
        return VersionTimestampDedup.uniqueInstant(Instant.now(), existing);
    }

    public WorkflowListItem toListItem(WorkflowDefinitionEntity def) {
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

    public Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(WorkflowErrorCode.PLAN_INVALID);
        }
    }

    public static void requireDescription(String description) {
        if (!StringUtils.hasText(description)) {
            throw new BizException(WorkflowErrorCode.DESCRIPTION_REQUIRED);
        }
    }

    public static String stringVal(Object raw) {
        return stringVal(raw, null);
    }

    public static String stringVal(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String text = raw.toString().strip();
        return text.isEmpty() ? fallback : text;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> castMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    public static List<String> stringList(Object raw) {
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

    public static String textOrNull(Object raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().strip();
        return text.isEmpty() ? null : text;
    }
}
