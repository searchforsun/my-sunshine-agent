package com.sunshine.oa.service;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.oa.dto.AdminTaskRequest;
import com.sunshine.oa.dto.AdminTaskVO;
import com.sunshine.oa.entity.OaTaskEntity;
import com.sunshine.oa.exception.OaErrorCode;
import com.sunshine.oa.model.OaTask;
import com.sunshine.oa.repo.OaTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OaBizService {

    private final OaTaskRepository taskRepository;

    @Transactional(readOnly = true)
    public List<OaTask> listTasks(String tenantId, String userId, String status) {
        String tenant = blankToDefault(tenantId);
        String user = blankToEmpty(userId);
        List<OaTaskEntity> rows;
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            rows = taskRepository.findByTenantIdAndAssigneeUserIdOrderByIdAsc(tenant, user);
        } else {
            rows = taskRepository.findByTenantIdAndAssigneeUserIdAndStatusOrderByIdAsc(
                    tenant, user, status.trim().toLowerCase(Locale.ROOT));
        }
        return rows.stream().map(this::toTask).toList();
    }

    @Transactional(readOnly = true)
    public Optional<OaTask> findTask(String tenantId, String userId, String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        return taskRepository
                .findByTenantIdAndAssigneeUserIdAndId(
                        blankToDefault(tenantId), blankToEmpty(userId), taskId.trim())
                .map(this::toTask);
    }

    @Transactional
    public Optional<OaTask> approveTask(String tenantId, String userId, String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        Optional<OaTaskEntity> found = taskRepository.findByTenantIdAndAssigneeUserIdAndId(
                blankToDefault(tenantId), blankToEmpty(userId), taskId.trim());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        OaTaskEntity entity = found.get();
        entity.setStatus("done");
        entity.setUpdatedAt(Instant.now());
        return Optional.of(toTask(taskRepository.save(entity)));
    }

    @Transactional(readOnly = true)
    public List<AdminTaskVO> adminListTasks(String tenantId, String userId, String status) {
        String tenant = blankToDefault(tenantId);
        boolean hasUser = StringUtils.hasText(userId);
        boolean hasStatus = StringUtils.hasText(status) && !"all".equalsIgnoreCase(status.trim());
        List<OaTaskEntity> rows;
        if (hasUser && hasStatus) {
            rows = taskRepository.findByTenantIdAndAssigneeUserIdAndStatusOrderByIdAsc(
                    tenant, userId.trim(), status.trim().toLowerCase(Locale.ROOT));
        } else if (hasUser) {
            rows = taskRepository.findByTenantIdAndAssigneeUserIdOrderByIdAsc(tenant, userId.trim());
        } else if (hasStatus) {
            rows = taskRepository.findByTenantIdAndStatusOrderByIdAsc(
                    tenant, status.trim().toLowerCase(Locale.ROOT));
        } else {
            rows = taskRepository.findByTenantIdOrderByIdAsc(tenant);
        }
        return rows.stream().map(this::toAdmin).toList();
    }

    @Transactional
    public AdminTaskVO adminCreateTask(AdminTaskRequest request) {
        requireTaskRequest(request);
        Instant now = Instant.now();
        OaTaskEntity entity = new OaTaskEntity();
        entity.setId("task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        entity.setTenantId(blankToDefault(request.tenantId()));
        entity.setAssigneeUserId(request.assigneeUserId().trim());
        entity.setTitle(request.title().trim());
        entity.setCategory(request.category().trim());
        entity.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toLowerCase(Locale.ROOT) : "pending");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toAdmin(taskRepository.save(entity));
    }

    @Transactional
    public AdminTaskVO adminUpdateTask(String id, AdminTaskRequest request) {
        if (!StringUtils.hasText(id)) {
            throw new BizException(OaErrorCode.TASK_NOT_FOUND);
        }
        requireTaskRequest(request);
        OaTaskEntity entity = taskRepository.findById(id.trim())
                .orElseThrow(() -> new BizException(OaErrorCode.TASK_NOT_FOUND));
        entity.setTenantId(blankToDefault(request.tenantId()));
        entity.setAssigneeUserId(request.assigneeUserId().trim());
        entity.setTitle(request.title().trim());
        entity.setCategory(request.category().trim());
        entity.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toLowerCase(Locale.ROOT) : entity.getStatus());
        entity.setUpdatedAt(Instant.now());
        return toAdmin(taskRepository.save(entity));
    }

    @Transactional
    public void adminDeleteTask(String id) {
        if (!StringUtils.hasText(id) || !taskRepository.existsById(id.trim())) {
            throw new BizException(OaErrorCode.TASK_NOT_FOUND);
        }
        taskRepository.deleteById(id.trim());
    }

    private void requireTaskRequest(AdminTaskRequest request) {
        if (request == null || !StringUtils.hasText(request.assigneeUserId())
                || !StringUtils.hasText(request.title())
                || !StringUtils.hasText(request.category())) {
            throw new BizException(OaErrorCode.INVALID_TASK_REQUEST);
        }
    }

    private AdminTaskVO toAdmin(OaTaskEntity e) {
        return new AdminTaskVO(
                e.getId(), e.getTenantId(), e.getAssigneeUserId(),
                e.getTitle(), e.getCategory(), e.getStatus());
    }

    private OaTask toTask(OaTaskEntity e) {
        return new OaTask(
                e.getId(), e.getTitle(), e.getCategory(), e.getStatus(), e.getAssigneeUserId());
    }

    private static String blankToDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }

    private static String blankToEmpty(String userId) {
        return userId == null ? "" : userId.trim();
    }
}
