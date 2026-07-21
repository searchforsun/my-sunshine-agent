package com.sunshine.oa.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.result.R;
import com.sunshine.oa.dto.AdminTaskRequest;
import com.sunshine.oa.dto.AdminTaskVO;
import com.sunshine.oa.service.OaBizService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 业务数据 Admin CRUD：鉴权 X-Admin-Token。 */
@RestController
@RequestMapping("/api/biz/oa")
@RequiredArgsConstructor
public class BizOaController {

    private final OaBizService oaBizService;

    @Value("${sunshine.biz.admin-token:sunshine-mock-admin-dev}")
    private String adminToken;

    @GetMapping("/tasks")
    public R<List<AdminTaskVO>> listTasks(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "status", required = false) String status) {
        requireAdmin(token);
        return R.ok(oaBizService.adminListTasks(tenantId, userId, status));
    }

    @PostMapping("/tasks")
    public R<AdminTaskVO> createTask(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestBody AdminTaskRequest request) {
        requireAdmin(token);
        return R.ok(oaBizService.adminCreateTask(request));
    }

    @PutMapping("/tasks/{id}")
    public R<AdminTaskVO> updateTask(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String id,
            @RequestBody AdminTaskRequest request) {
        requireAdmin(token);
        return R.ok(oaBizService.adminUpdateTask(id, request));
    }

    @DeleteMapping("/tasks/{id}")
    public R<Map<String, String>> deleteTask(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @PathVariable String id) {
        requireAdmin(token);
        oaBizService.adminDeleteTask(id);
        return R.ok(Map.of("id", id, "status", "deleted"));
    }

    private void requireAdmin(String token) {
        if (!StringUtils.hasText(token) || !adminToken.equals(token)) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
