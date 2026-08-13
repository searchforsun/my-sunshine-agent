package com.sunshine.bizscene.controller;

import com.sunshine.bizscene.dto.BizSceneCreateRequest;
import com.sunshine.bizscene.dto.BizSceneDefinitionView;
import com.sunshine.bizscene.dto.BizScenePolicySaveRequest;
import com.sunshine.bizscene.dto.BizScenePolicyView;
import com.sunshine.bizscene.dto.BizSceneUpdateRequest;
import com.sunshine.bizscene.service.BizSceneAdminService;
import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/biz-scenes")
@RequiredArgsConstructor
public class BizSceneAdminController {

    private final BizSceneAdminService bizSceneAdminService;

    @GetMapping
    public R<List<BizSceneDefinitionView>> list() {
        return R.ok(bizSceneAdminService.listScenes());
    }

    @GetMapping("/active-codes")
    public R<List<String>> listActiveCodes() {
        return R.ok(bizSceneAdminService.listActiveCodes());
    }

    @PostMapping
    public R<BizSceneDefinitionView> create(@RequestBody BizSceneCreateRequest request) {
        return R.ok(bizSceneAdminService.createScene(request));
    }

    @PutMapping("/{bizScene}")
    public R<BizSceneDefinitionView> update(@PathVariable String bizScene,
            @RequestBody BizSceneUpdateRequest request) {
        return R.ok(bizSceneAdminService.updateScene(bizScene, request));
    }

    @GetMapping("/policies")
    public R<List<BizScenePolicyView>> listPolicies(@RequestParam(defaultValue = "default") String tenantId) {
        return R.ok(bizSceneAdminService.listPolicies(tenantId));
    }

    @PostMapping("/policies")
    public R<BizScenePolicyView> createPolicy(@RequestParam(defaultValue = "default") String tenantId,
            @RequestBody BizScenePolicySaveRequest request) {
        return R.ok(bizSceneAdminService.createPolicy(tenantId, request));
    }
}
