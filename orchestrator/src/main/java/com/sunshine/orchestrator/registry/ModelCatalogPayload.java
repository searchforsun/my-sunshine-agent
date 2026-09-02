package com.sunshine.orchestrator.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** resource-manager GET /api/models/catalog 的 data 载荷（无密钥） */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelCatalogPayload(
        List<?> providers,
        List<ModelCatalogDefinition> definitions,
        List<ModelCatalogScene> scenes
) {
}
