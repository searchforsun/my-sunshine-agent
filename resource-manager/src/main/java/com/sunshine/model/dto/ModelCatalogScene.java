package com.sunshine.model.dto;

import java.util.Map;

public record ModelCatalogScene(
        String sceneKey,
        String primaryModel,
        String fallbackModel,
        Map<String, Object> extras,
        boolean enabled
) {
}
