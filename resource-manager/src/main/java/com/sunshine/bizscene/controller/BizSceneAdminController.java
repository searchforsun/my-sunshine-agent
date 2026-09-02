package com.sunshine.bizscene.controller;

import com.sunshine.bizscene.dto.BizSceneCreateRequest;
import com.sunshine.bizscene.dto.BizSceneDefinitionView;
import com.sunshine.bizscene.dto.BizSceneEmbeddingItem;
import com.sunshine.bizscene.dto.BizScenePolicySaveRequest;
import com.sunshine.bizscene.dto.BizScenePolicyView;
import com.sunshine.bizscene.dto.BizSceneUpdateRequest;
import com.sunshine.bizscene.dto.BizSceneVectorRequest;
import com.sunshine.bizscene.service.BizSceneAdminService;
import com.sunshine.common.core.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /** 场景 embedding 检索索引（authority §2.1b/§4.4）：供 orchestrator 缓存余弦匹配。 */
    @GetMapping("/embedding-index")
    public R<List<BizSceneEmbeddingItem>> listEmbeddingIndex() {
        return R.ok(bizSceneAdminService.listEmbeddingIndex());
    }

    /** 场景 description 向量回填（orchestrator 计算后推送）。 */
    @PutMapping("/{bizScene}/vector")
    public R<Void> updateVector(@PathVariable String bizScene, @RequestBody BizSceneVectorRequest request) {
        bizSceneAdminService.updateVector(bizScene, request);
        return R.ok();
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

    @DeleteMapping("/{bizScene}")
    public R<Void> delete(@PathVariable String bizScene) {
        bizSceneAdminService.deleteScene(bizScene);
        return R.ok();
    }

    @GetMapping("/policies")
    public R<List<BizScenePolicyView>> listPolicies(@RequestParam(defaultValue = "default") String tenantId) {
        return R.ok(bizSceneAdminService.listPolicies(tenantId));
    }

    /** 运行时装载端点（authority §4.2）：全租户 active Policy 快照，供 orchestrator 缓存 */
    @GetMapping("/policies/active")
    public R<List<BizScenePolicyView>> listActivePolicies() {
        return R.ok(bizSceneAdminService.listActivePolicies());
    }

    @PostMapping("/policies")
    public R<BizScenePolicyView> createPolicy(@RequestParam(defaultValue = "default") String tenantId,
            @RequestBody BizScenePolicySaveRequest request) {
        return R.ok(bizSceneAdminService.createPolicy(tenantId, request));
    }

    @DeleteMapping("/policies/{policyId}")
    public R<Void> deletePolicy(@PathVariable Long policyId) {
        bizSceneAdminService.deletePolicy(policyId);
        return R.ok();
    }
}
