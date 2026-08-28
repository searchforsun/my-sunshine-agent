package com.sunshine.model.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.common.model.CallSiteKey;
import com.sunshine.model.dto.ModelRouteResponse;
import com.sunshine.model.dto.ModelRouteUpsertRequest;
import com.sunshine.model.service.ModelRoutePolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/models/routes")
@RequiredArgsConstructor
public class ModelRoutePolicyController {

    private final ModelRoutePolicyService routePolicyService;

    /** 调用点枚举清单（只读）；前端下拉与描述 SSOT */
    @GetMapping("/keys")
    public R<List<CallSiteKey>> keys() {
        return R.ok(List.of(CallSiteKey.values()));
    }

    @GetMapping
    public R<List<ModelRouteResponse>> list(@RequestParam(required = false) String tenantId) {
        return R.ok(routePolicyService.list(tenantId));
    }

    @PutMapping
    public R<ModelRouteResponse> upsert(@RequestBody ModelRouteUpsertRequest request) {
        return R.ok(routePolicyService.upsertOne(request));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        routePolicyService.delete(id);
        return R.ok();
    }
}
