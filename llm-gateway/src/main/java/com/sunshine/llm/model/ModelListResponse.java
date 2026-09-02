package com.sunshine.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** OpenAI 兼容模型列表响应（含上下文窗口与 capabilities，无密钥）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelListResponse {

    private String object;
    private List<ModelInfo> data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModelInfo {
        private String id;
        @JsonProperty("display_name")
        private String displayName;
        @JsonProperty("context_window")
        private Integer contextWindow;
        private String encoding;
        private Map<String, Boolean> capabilities;
        private String provider;
        @JsonProperty("user_selectable")
        private Boolean userSelectable;
    }
}
