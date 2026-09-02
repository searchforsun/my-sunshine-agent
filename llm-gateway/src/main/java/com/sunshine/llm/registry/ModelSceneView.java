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
public class ModelSceneView {

    @JsonProperty("scene_key")
    @JsonAlias("sceneKey")
    private String sceneKey;

    @JsonProperty("primary_model")
    @JsonAlias("primaryModel")
    private String primaryModel;

    @JsonProperty("fallback_model")
    @JsonAlias("fallbackModel")
    private String fallbackModel;

    private Object extras;

    private boolean enabled;
}
