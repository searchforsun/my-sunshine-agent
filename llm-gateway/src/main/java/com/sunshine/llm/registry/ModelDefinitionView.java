package com.sunshine.llm.registry;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelDefinitionView {

    @JsonProperty("provider_key")
    @JsonAlias("providerKey")
    private String providerKey;

    @JsonProperty("model_name")
    @JsonAlias({"modelName", "id", "name"})
    private String modelName;

    @JsonProperty("display_name")
    @JsonAlias("displayName")
    private String displayName;

    @JsonProperty("context_window")
    @JsonAlias("contextWindow")
    private int contextWindow;

    @JsonProperty("max_output_tokens")
    @JsonAlias("maxOutputTokens")
    private int maxOutputTokens;

    private String encoding;

    private ModelCapabilities capabilities;

    /** OpenAI 兼容请求缺省参数（缺键合并进上游 body） */
    @JsonProperty("request_extras")
    @JsonAlias("requestExtras")
    private Map<String, Object> requestExtras;

    @JsonProperty("user_selectable")
    @JsonAlias("userSelectable")
    private boolean userSelectable;

    private boolean enabled;

    @JsonProperty("sort_order")
    @JsonAlias("sortOrder")
    private int sortOrder;
}
