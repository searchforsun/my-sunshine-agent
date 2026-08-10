package com.sunshine.llm.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayModelCatalog {

    @Builder.Default
    private List<ModelProviderView> providers = new ArrayList<>();

    @Builder.Default
    private List<ModelDefinitionView> definitions = new ArrayList<>();

    @Builder.Default
    private List<ModelSceneView> scenes = new ArrayList<>();
}
