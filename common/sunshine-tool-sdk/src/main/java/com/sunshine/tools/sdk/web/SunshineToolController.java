package com.sunshine.tools.sdk.web;

import com.sunshine.tools.sdk.dto.SdkToolCatalogResponse;
import com.sunshine.tools.sdk.dto.SdkToolInvokeResponse;
import com.sunshine.tools.sdk.registry.SunshineToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/sunshine/tools")
public class SunshineToolController {
    private final SunshineToolRegistry registry;

    public SunshineToolController(SunshineToolRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/catalog")
    public SdkToolCatalogResponse catalog() {
        return registry.catalog();
    }

    @PostMapping("/invoke/{toolId}")
    public SdkToolInvokeResponse invoke(@PathVariable String toolId, @RequestBody Map<String, String> params) {
        return registry.invoke(toolId, params);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
