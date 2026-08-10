package com.sunshine.model.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.model.dto.ModelCatalogResponse;
import com.sunshine.model.service.ModelCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models/catalog")
@RequiredArgsConstructor
public class ModelCatalogController {
    private final ModelCatalogService modelCatalogService;

    @GetMapping
    public R<ModelCatalogResponse> publicCatalog(@RequestParam(required = false) String tenantId) {
        return R.ok(modelCatalogService.publicCatalog(tenantId));
    }

    @GetMapping("/gateway")
    public R<ModelCatalogResponse> gatewayCatalog(@RequestParam(required = false) String tenantId) {
        return R.ok(modelCatalogService.gatewayCatalog(tenantId));
    }
}
