package com.sunshine.llm.registry;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelCapabilities {

    @Builder.Default
    private boolean reasoning = false;
    @Builder.Default
    private boolean multimodal = false;
    @JsonProperty("tool_call")
    @JsonAlias("toolCall")
    @Builder.Default
    private boolean toolCall = false;

    public static ModelCapabilities defaults() {
        return ModelCapabilities.builder().build();
    }
}
