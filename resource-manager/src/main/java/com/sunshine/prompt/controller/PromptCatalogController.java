package com.sunshine.prompt.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.prompt.dto.PromptCatalogResponse;
import com.sunshine.prompt.service.PromptCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时 Catalog — 字面量 {@code /catalog}，避免被 {@code GET /{id}} 吞掉。
 */
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptCatalogController {
    private final PromptCatalogService promptCatalogService;

    @GetMapping("/catalog")
    public R<PromptCatalogResponse> catalog() {
        return R.ok(promptCatalogService.catalog());
    }

    /** 轻量版本接口：不携带 prompt 正文，供消费方先比对版本再拉全量 catalog */
    @GetMapping("/catalog/version")
    public R<Long> catalogVersion() {
        return R.ok(promptCatalogService.catalogVersion());
    }
}
