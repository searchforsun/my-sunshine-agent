package com.sunshine.model.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.model.dto.ModelDefinitionRequest;
import com.sunshine.model.dto.ModelDefinitionResponse;
import com.sunshine.model.service.ModelDefinitionService;
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
@RequestMapping("/api/models/definitions")
@RequiredArgsConstructor
public class ModelDefinitionController {
    private final ModelDefinitionService modelDefinitionService;

    @GetMapping
    public R<List<ModelDefinitionResponse>> list(@RequestParam(required = false) String tenantId) {
        return R.ok(modelDefinitionService.list(tenantId));
    }

    @PostMapping
    public R<ModelDefinitionResponse> create(@RequestBody ModelDefinitionRequest request) {
        return R.ok(modelDefinitionService.create(request));
    }

    @PutMapping("/{id}")
    public R<ModelDefinitionResponse> update(@PathVariable Long id, @RequestBody ModelDefinitionRequest request) {
        return R.ok(modelDefinitionService.update(id, request));
    }

    @PostMapping("/{id}/toggle")
    public R<ModelDefinitionResponse> toggle(@PathVariable Long id) {
        return R.ok(modelDefinitionService.toggle(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        modelDefinitionService.delete(id);
        return R.ok(null);
    }
}
