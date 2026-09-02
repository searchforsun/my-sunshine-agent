package com.sunshine.orchestrator.prompt;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Live / 运维：观测本地 Catalog Snapshot 版本（热更新是否追上 prompt-manager）。 */
@RestController
@RequestMapping("/api/prompt-catalog")
@RequiredArgsConstructor
public class PromptCatalogStatusController {

    private final PromptCatalogHolder promptCatalogHolder;

    @GetMapping("/version")
    public Map<String, Long> version() {
        return Map.of("catalogVersion", promptCatalogHolder.snapshot().catalogVersion());
    }
}
