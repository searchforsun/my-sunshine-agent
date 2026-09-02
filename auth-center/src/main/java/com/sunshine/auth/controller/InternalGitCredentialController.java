package com.sunshine.auth.controller;

import com.sunshine.auth.service.UserService;
import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务间 Git 凭据查询端点（orchestrator → auth）。
 * 令牌不出 auth-center，仅返回匹配的单条凭据。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class InternalGitCredentialController {

    private final UserService userService;

    @GetMapping("/git-credentials")
    public R<Map<String, String>> gitCredentials(
            @RequestParam String host,
            @RequestHeader("x-user-id") String userId) {
        Map<String, String> cred = userService.findGitCredential(userId, host);
        return R.ok(cred);
    }
}
