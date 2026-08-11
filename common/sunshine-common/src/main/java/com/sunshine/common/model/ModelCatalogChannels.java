package com.sunshine.common.model;

/** Redis pub/sub：模型 Catalog 变更通知（resource-manager → llm-gateway / orchestrator）。 */
public final class ModelCatalogChannels {

    public static final String CHANGED = "model-catalog-changed";

    private ModelCatalogChannels() {
    }
}
