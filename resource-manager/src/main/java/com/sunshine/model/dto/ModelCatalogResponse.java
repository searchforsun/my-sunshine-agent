package com.sunshine.model.dto;

import java.util.List;

public record ModelCatalogResponse(
        List<ModelCatalogProvider> providers,
        List<ModelCatalogDefinition> definitions,
        List<ModelCatalogScene> scenes,
        List<ModelCatalogRoute> routes
) {
}
