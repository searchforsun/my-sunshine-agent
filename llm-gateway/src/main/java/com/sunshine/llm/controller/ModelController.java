package com.sunshine.llm.controller;

import com.sunshine.llm.config.ProviderProperties;
import com.sunshine.llm.model.ModelListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 模型元信息端点：聚合所有 provider 的模型 + 上下文窗口。 */
@Slf4j
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ModelController {

    private final ProviderProperties providerProperties;

    @GetMapping("/models")
    public ModelListResponse listModels() {
        List<ModelListResponse.ModelInfo> data = new ArrayList<>();
        if (providerProperties.getProviders() != null) {
            providerProperties.getProviders().values().forEach(config -> {
                if (config.getModels() != null) {
                    config.getModels().forEach(m -> data.add(
                            ModelListResponse.ModelInfo.builder()
                                    .id(m.getName())
                                    .contextWindow(m.getContextWindow())
                                    .encoding(m.getEncoding())
                                    .build()));
                }
            });
        }
        return ModelListResponse.builder().object("list").data(data).build();
    }
}
