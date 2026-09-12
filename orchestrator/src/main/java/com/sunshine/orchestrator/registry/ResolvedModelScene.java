package com.sunshine.orchestrator.registry;

import java.util.Map;

/**
 * 场景解析结果。{@code overrideInvalid} 在会话所选模型无效/停用并回落场景链时为 true（供时间线 warning）。
 * <p>{@code extras} = 模型级 {@code request_extras} 叠加场景 extras（场景覆盖同名键）。
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
