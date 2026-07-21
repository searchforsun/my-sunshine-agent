package com.sunshine.hr.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import com.sunshine.common.core.result.R;
import com.sunshine.hr.store.HrTenantUserStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 联调 Admin：按用户查看 / 重置 HR 种子数据。鉴权：X-Admin-Token。 */
@RestController
@RequestMapping("/api/mock/hr")
@RequiredArgsConstructor
public class MockAdminController {

    private final HrTenantUserStore store;

    @Value("${sunshine.mock.admin-token:sunshine-mock-admin-dev}")
    private String adminToken;

    @GetMapping("/users")
    public R<List<String>> listUsers(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId) {
        requireAdmin(token);
        return R.ok(store.listUserIds(tenantId));
    }

    @GetMapping("/snapshot")
    public R<Map<String, Object>> snapshot(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam("userId") String userId,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId) {
        requireAdmin(token);
        if (!StringUtils.hasText(userId)) {
            throw new BizException(CommonErrorCode.BAD_REQUEST);
        }
        return R.ok(store.snapshot(tenantId, userId.trim()));
    }

    @PostMapping("/reset")
    public R<Map<String, String>> reset(
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "tenantId", required = false, defaultValue = "default") String tenantId) {
        requireAdmin(token);
        store.reset(tenantId);
        return R.ok(Map.of("tenantId", StringUtils.hasText(tenantId) ? tenantId.trim() : "default", "status", "reset"));
    }

    private void requireAdmin(String token) {
        if (!StringUtils.hasText(token) || !adminToken.equals(token)) {
            throw new BizException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
