package com.sunshine.orchestrator.registry;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 注册表 capabilities；与 resource-manager Catalog 对齐 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelCapabilities(
        boolean reasoning,
        boolean multimodal,
        @JsonProperty("toolCall")
        @JsonAlias("tool_call")
        boolean toolCall
) {
    public static ModelCapabilities defaults() {
        return new ModelCapabilities(false, false, true);
    }
}
