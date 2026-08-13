package com.sunshine.bff.controller;

import com.sunshine.bff.client.SkillManagerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传业务场景 Lab 码表（K2：Skill/Agent 表单下拉；完整 Lab 管理在侧栏 Lab 页） */
@RestController
@RequiredArgsConstructor
public class BizScenesController {

    private final SkillManagerClient skillManagerClient;

    @GetMapping("/api/biz-scenes/active-codes")
    public Mono<Map<String, Object>> listActiveCodes() {
        return skillManagerClient.activeBizSceneCodes();
    }
}
