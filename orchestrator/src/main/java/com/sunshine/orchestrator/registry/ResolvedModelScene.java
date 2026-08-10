package com.sunshine.orchestrator.registry;

import java.util.Map;

/**
 * 场景解析结果。{@code overrideInvalid} 仅 {@link ModelSceneResolver#resolveChat} 在
 * 会话模型停用/缺失并回落 chat/default 时为 true（供时间线 warning）。
 */
public record ResolvedModelScene(
        String effectiveModel,
        String fallbackModel,
        Map<String, Object> extras,
        int contextWindow,
        int maxOutputTokens,
        ModelCapabilities capabilities,
        boolean overrideInvalid
) {
    public ResolvedModelScene {
        extras = extras != null ? Map.copyOf(extras) : Map.of();
        capabilities = capabilities != null ? capabilities : ModelCapabilities.defaults();
    }

    public ResolvedModelScene withOverrideInvalid(boolean invalid) {
        return new ResolvedModelScene(
                effectiveModel, fallbackModel, extras, contextWindow, maxOutputTokens, capabilities, invalid);
    }
}
