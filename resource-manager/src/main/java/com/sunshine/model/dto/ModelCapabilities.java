package com.sunshine.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

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
