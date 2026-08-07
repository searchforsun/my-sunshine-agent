package com.sunshine.oa.controller;

import com.sunshine.common.biz.BizAdminAuth;
import com.sunshine.common.core.result.R;
import com.sunshine.oa.dto.AdminTaskRequest;
import com.sunshine.oa.dto.AdminTaskVO;
import com.sunshine.oa.service.OaBizService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

/** 业务数据 Admin CRUD：鉴权 {@link BizAdminAuth#HEADER}。 */
@RestController
@RequestMapping("/api/biz/oa")
@RequiredArgsConstructor
public class BizOaController {

    private final OaBizService oaBizService;

    @Value(BizAdminAuth.TOKEN_PROPERTY)
    private String adminToken;

    @GetMapping("/tasks")
    public R<List<AdminTaskVO>> listTasks(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "status", required = false) String status) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(oaBizService.adminListTasks(tenantId, userId, status));
    }

    @PostMapping("/tasks")
    public R<AdminTaskVO> createTask(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @RequestBody AdminTaskRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(oaBizService.adminCreateTask(request));
    }

    @PutMapping("/tasks/{id}")
    public R<AdminTaskVO> updateTask(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String id,
            @RequestBody AdminTaskRequest request) {
        BizAdminAuth.require(token, adminToken);
        return R.ok(oaBizService.adminUpdateTask(id, request));
    }

    @DeleteMapping("/tasks/{id}")
    public R<Map<String, String>> deleteTask(
            @RequestHeader(value = BizAdminAuth.HEADER, required = false) String token,
            @PathVariable String id) {
        BizAdminAuth.require(token, adminToken);
        oaBizService.adminDeleteTask(id);
        return R.ok(Map.of("id", id, "status", "deleted"));
    }
}
