package com.sunshine.llm.controller;

import com.sunshine.llm.model.ModelListResponse;
import com.sunshine.llm.registry.ModelCapabilities;
import com.sunshine.llm.registry.ModelDefinitionView;
import com.sunshine.llm.registry.ModelRegistryCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型元信息端点：来自注册表 Cache（无密钥）。 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ModelController {

    private final ModelRegistryCache registryCache;

    @GetMapping("/models")
    public ModelListResponse listModels() {
        List<ModelListResponse.ModelInfo> data = registryCache.listEnabledDefinitions().stream()
                .map(this::toInfo)
                .toList();
        return ModelListResponse.builder().object("list").data(data).build();
    }

    private ModelListResponse.ModelInfo toInfo(ModelDefinitionView def) {
        ModelCapabilities caps = def.getCapabilities() != null
                ? def.getCapabilities()
                : ModelCapabilities.defaults();
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        capabilities.put("reasoning", caps.isReasoning());
        capabilities.put("multimodal", caps.isMultimodal());
        capabilities.put("tool_call", caps.isToolCall());
        return ModelListResponse.ModelInfo.builder()
                .id(def.getModelName())
                .displayName(def.getDisplayName())
                .contextWindow(def.getContextWindow())
                .encoding(def.getEncoding())
                .capabilities(capabilities)
                .provider(def.getProviderKey())
                .userSelectable(def.isUserSelectable())
                .build();
    }
}
