package com.sunshine.bff.controller;

import com.sunshine.bff.client.SkillManagerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传业务场景 Lab（K2）：码表 CRUD + Policy；active-codes 供 Skill/Agent 表单下拉 */
@RestController
@RequiredArgsConstructor
public class BizScenesController {

    private final SkillManagerClient skillManagerClient;

    @GetMapping("/api/biz-scenes")
    public Mono<Map<String, Object>> list() {
        return skillManagerClient.listBizScenes();
    }

    @GetMapping("/api/biz-scenes/active-codes")
    public Mono<Map<String, Object>> listActiveCodes() {
        return skillManagerClient.activeBizSceneCodes();
    }

    @PostMapping("/api/biz-scenes")
    public Mono<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return skillManagerClient.createBizScene(body);
    }

    @PutMapping("/api/biz-scenes/{code}")
    public Mono<Map<String, Object>> update(@PathVariable String code, @RequestBody Map<String, Object> body) {
        return skillManagerClient.updateBizScene(code, body);
    }

    @DeleteMapping("/api/biz-scenes/{code}")
    public Mono<Map<String, Object>> delete(@PathVariable String code) {
        return skillManagerClient.deleteBizScene(code);
    }

    @GetMapping("/api/biz-scenes/policies")
    public Mono<Map<String, Object>> listPolicies(@RequestParam(defaultValue = "default") String tenantId) {
        return skillManagerClient.listBizScenePolicies(tenantId);
    }

    @PostMapping("/api/biz-scenes/policies")
    public Mono<Map<String, Object>> createPolicy(
            @RequestParam(defaultValue = "default") String tenantId,
            @RequestBody Map<String, Object> body) {
        return skillManagerClient.createBizScenePolicy(tenantId, body);
    }

    @DeleteMapping("/api/biz-scenes/policies/{policyId}")
    public Mono<Map<String, Object>> deletePolicy(@PathVariable Long policyId) {
        return skillManagerClient.deleteBizScenePolicy(policyId);
    }
}
