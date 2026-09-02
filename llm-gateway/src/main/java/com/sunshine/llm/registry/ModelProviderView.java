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
public class ModelProviderView {

    @JsonProperty("provider_key")
    @JsonAlias("providerKey")
    private String providerKey;

    @JsonProperty("display_name")
    @JsonAlias("displayName")
    private String displayName;

    private String protocol;

    @JsonProperty("base_url")
    @JsonAlias("baseUrl")
    private String baseUrl;

    @JsonProperty("path_prefix")
    @JsonAlias("pathPrefix")
    private String pathPrefix;

    @JsonProperty("api_key_enc")
    @JsonAlias("apiKeyEnc")
    private String apiKeyEnc;

    private boolean enabled;
}
