package com.sunshine.model.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.model.dto.ModelProviderRequest;
import com.sunshine.model.dto.ModelProviderResponse;
import com.sunshine.model.service.ModelProviderService;
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
@RequestMapping("/api/models/providers")
@RequiredArgsConstructor
public class ModelProviderController {
    private final ModelProviderService modelProviderService;

    @GetMapping
    public R<List<ModelProviderResponse>> list(@RequestParam(required = false) String tenantId) {
        return R.ok(modelProviderService.list(tenantId));
    }

    @PostMapping
    public R<ModelProviderResponse> create(@RequestBody ModelProviderRequest request) {
        return R.ok(modelProviderService.create(request));
    }

    @PutMapping("/{id}")
    public R<ModelProviderResponse> update(@PathVariable Long id, @RequestBody ModelProviderRequest request) {
        return R.ok(modelProviderService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        modelProviderService.delete(id);
        return R.ok(null);
    }
}
