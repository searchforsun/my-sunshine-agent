package com.sunshine.orchestrator.context;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型上下文窗口缓存：Gateway /v1/models 响应刷新；未命中/未刷新降级 defaultModelWindow。
 */
@Component
public class ModelWindowCache {

    private final ContextProperties contextProperties;
    private final AtomicReference<Map<String, Integer>> windows = new AtomicReference<>(Map.of());

    @Value("${agent.model.base-url:http://127.0.0.1:8300/v1}")
    private String gatewayBaseUrl;

    private WebClient webClient;

    public ModelWindowCache(ContextProperties contextProperties) {
        this.contextProperties = contextProperties;
    }

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder().baseUrl(gatewayBaseUrl).build();
        refreshFromGateway();
    }

    /** 模型上下文窗口；未命中降级 defaultModelWindow。 */
    public int windowFor(String model) {
        if (model != null) {
            Integer w = windows.get().get(model);
            if (w != null && w > 0) {
                return w;
            }
        }
        return contextProperties.getL1().getDefaultModelWindow();
    }

    /** 用 Gateway /v1/models 响应整体替换缓存。 */
    public void refresh(Map<String, Integer> newWindows) {
        windows.set(newWindows != null
                ? Map.copyOf(new ConcurrentHashMap<>(newWindows))
                : Map.of());
    }

    /** 启动/刷新时从 Gateway /v1/models 拉取模型窗口；失败保留旧缓存（降级 defaultModelWindow）。 */
    public void refreshFromGateway() {
        try {
            ModelListDto resp = webClient.get()
                    .uri("/models")
                    .retrieve()
                    .bodyToMono(ModelListDto.class)
                    .block(Duration.ofSeconds(5));
            if (resp != null && resp.data() != null && !resp.data().isEmpty()) {
                Map<String, Integer> map = new HashMap<>();
                for (ModelInfoDto d : resp.data()) {
                    if (d.id() != null && d.contextWindow() != null && d.contextWindow() > 0) {
                        map.put(d.id(), d.contextWindow());
                    }
                }
                if (!map.isEmpty()) {
                    refresh(map);
                }
            }
        } catch (Exception e) {
            // Gateway 不可用：保留旧缓存，windowFor 降级 defaultModelWindow
            LoggerFactory.getLogger(ModelWindowCache.class)
                    .warn("[ModelWindowCache] refresh 失败，降级默认窗口: {}", e.getMessage());
        }
    }

    record ModelListDto(String object, List<ModelInfoDto> data) {
    }

    record ModelInfoDto(String id,
                        @JsonProperty("context_window") Integer contextWindow,
                        String encoding) {
    }
}
