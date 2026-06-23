package com.sunshine.auth.controller;

import com.sunshine.auth.dto.UserBriefVO;
import com.sunshine.auth.service.UserService;
import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/** 服务间用户简要信息 — 仅内网调用，不经 Gateway 暴露 */
@RestController
@RequestMapping("/api/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserService userService;

    @GetMapping("/brief")
    public R<List<UserBriefVO>> brief(@RequestParam("ids") String ids) {
        List<String> userIds = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        return R.ok(userService.findBriefByIds(userIds));
    }
}
