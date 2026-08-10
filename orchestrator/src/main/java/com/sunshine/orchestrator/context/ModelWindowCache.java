package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.LlmGatewayClient.ModelInfoDto;
import com.sunshine.orchestrator.client.LlmGatewayClient.ModelListDto;
import com.sunshine.orchestrator.registry.ModelSceneResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型上下文窗口缓存：优先注册表 Catalog；可选从 Gateway /v1/models 补齐。
 * 缺 meta 时不再读 Nacos default-model-window。
 */
@Component
public class ModelWindowCache {

    private final LlmGatewayClient llmGateway;
    private final ModelSceneResolver modelSceneResolver;
    private final AtomicReference<Map<String, Integer>> windows = new AtomicReference<>(Map.of());

    public ModelWindowCache(LlmGatewayClient llmGateway, ModelSceneResolver modelSceneResolver) {
        this.llmGateway = llmGateway;
        this.modelSceneResolver = modelSceneResolver;
    }

    @PostConstruct
    public void init() {
        syncFromRegistry();
        refreshFromGateway();
    }

    /** 模型上下文窗口；未命中则查注册表，仍无则 fail-fast。 */
    public int windowFor(String model) {
        if (model != null) {
            Integer w = windows.get().get(model);
            if (w != null && w > 0) {
                return w;
            }
        }
        if (modelSceneResolver != null && model != null) {
            return modelSceneResolver.contextWindowFor(model);
        }
        throw new IllegalStateException("model context window unavailable: " + model);
    }

    /** 用窗口 map 整体替换缓存。 */
    public void refresh(Map<String, Integer> newWindows) {
        windows.set(newWindows != null
                ? Map.copyOf(new ConcurrentHashMap<>(newWindows))
                : Map.of());
    }

    public void syncFromRegistry() {
        try {
            Map<String, Integer> fromRegistry = modelSceneResolver.allContextWindows();
            if (!fromRegistry.isEmpty()) {
                refresh(fromRegistry);
            }
        } catch (Exception e) {
            LoggerFactory.getLogger(ModelWindowCache.class)
                    .warn("[ModelWindowCache] syncFromRegistry 失败: {}", e.getMessage());
        }
    }

    /** 启动/刷新时从 Gateway /v1/models 补齐；失败保留注册表缓存。 */
    public void refreshFromGateway() {
        try {
            ModelListDto resp = llmGateway.listModels().block();
            if (resp != null && resp.data() != null && !resp.data().isEmpty()) {
                Map<String, Integer> map = new HashMap<>(windows.get());
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
            LoggerFactory.getLogger(ModelWindowCache.class)
                    .warn("[ModelWindowCache] refreshFromGateway 失败: {}", e.getMessage());
        }
    }
}
