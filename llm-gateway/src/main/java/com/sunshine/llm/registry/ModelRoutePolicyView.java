package com.sunshine.llm.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** llm-gateway 侧路由策略视图：call_site → 候选模型池（首可用）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelRoutePolicyView {

    private String callSite;

    @Builder.Default
    private List<String> models = new ArrayList<>();

    private String strategy;

    private boolean enabled;
}
