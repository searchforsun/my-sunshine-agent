package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.LlmGatewayClient.ModelInfoDto;
import com.sunshine.orchestrator.client.LlmGatewayClient.ModelListDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型上下文窗口缓存：Gateway /v1/models 响应刷新；未命中/未刷新降级 defaultModelWindow。
 */
@Component
public class ModelWindowCache {

    private final ContextProperties contextProperties;
    private final LlmGatewayClient llmGateway;
    private final AtomicReference<Map<String, Integer>> windows = new AtomicReference<>(Map.of());

    public ModelWindowCache(ContextProperties contextProperties, LlmGatewayClient llmGateway) {
        this.contextProperties = contextProperties;
        this.llmGateway = llmGateway;
    }

    @PostConstruct
    public void init() {
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
            ModelListDto resp = llmGateway.listModels().block();
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
}
