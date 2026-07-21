package com.sunshine.oa.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.oa.exception.OaErrorCode;
import com.sunshine.oa.model.OaTask;
import com.sunshine.oa.store.OaTenantUserStore;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/oa")
@RequiredArgsConstructor
public class OaTaskController {

    private final OaTenantUserStore store;

    @GetMapping("/tasks")
    public R<List<OaTask>> listTasks(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @RequestParam(value = "status", required = false) String status) {
        String uid = requireUser(userId);
        return R.ok(store.listTasks(tenantOrDefault(tenantId), uid, status));
    }

    @PostMapping("/tasks/{taskId}/approve")
    public R<OaTask> approveTask(
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", required = false) String tenantId,
            @PathVariable String taskId) {
        String uid = requireUser(userId);
        return R.ok(store.approveTask(tenantOrDefault(tenantId), uid, taskId)
                .orElseThrow(() -> new BizException(OaErrorCode.TASK_NOT_FOUND)));
    }

    private static String requireUser(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BizException(OaErrorCode.USER_REQUIRED);
        }
        return userId.trim();
    }

    private static String tenantOrDefault(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : "default";
    }
}
