package com.sunshine.model.dto;

import java.util.List;

/** llm-gateway catalog 携带的路由策略视图（不含租户等内部字段）。 */
public record ModelCatalogRoute(
        String callSite,
        List<String> models,
        String strategy,
        boolean enabled
) {
}
