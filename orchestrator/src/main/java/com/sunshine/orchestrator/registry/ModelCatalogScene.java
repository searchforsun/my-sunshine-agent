package com.sunshine.orchestrator.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelCatalogScene(
        String sceneKey,
        String primaryModel,
        String fallbackModel,
        Map<String, Object> extras,
        boolean enabled
) {
}
