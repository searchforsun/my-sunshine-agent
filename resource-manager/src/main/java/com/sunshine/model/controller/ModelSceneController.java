package com.sunshine.model.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunshine.common.core.result.R;
import com.sunshine.model.dto.ModelSceneKeyMeta;
import com.sunshine.model.dto.ModelSceneResponse;
import com.sunshine.model.service.ModelSceneService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/models/scenes")
@RequiredArgsConstructor
public class ModelSceneController {
    private final ModelSceneService modelSceneService;

    /** 枚举清单（只读）；前端下拉与描述 SSOT */
    @GetMapping("/keys")
    public R<List<ModelSceneKeyMeta>> keys() {
        return R.ok(modelSceneService.listKeys());
    }

    @GetMapping
    public R<List<ModelSceneResponse>> list(@RequestParam(required = false) String tenantId) {
        return R.ok(modelSceneService.list(tenantId));
    }

    @PutMapping
    public R<List<ModelSceneResponse>> upsert(@RequestBody JsonNode body) {
        return R.ok(modelSceneService.upsert(body));
    }
}
