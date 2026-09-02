package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.registry.ModelSceneResolver;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型上下文窗口缓存 — 唯一 SSOT：注册表 Catalog（经 ModelSceneResolver）。
 * 不绕 llm-gateway（其数据源同为注册表，双路径已收口）。
 */
@Component
public class ModelWindowCache {

    private final ModelSceneResolver modelSceneResolver;
    private final AtomicReference<Map<String, Integer>> windows = new AtomicReference<>(Map.of());

    public ModelWindowCache(ModelSceneResolver modelSceneResolver) {
        this.modelSceneResolver = modelSceneResolver;
    }

    @PostConstruct
    public void init() {
        syncFromRegistry();
    }

    /** 模型上下文窗口；缓存 miss 则查注册表，仍无则 fail-fast。 */
    public int windowFor(String model) {
        if (model == null) {
            throw new IllegalStateException("model context window unavailable: " + model);
        }
        Integer w = windows.get().get(model);
        if (w != null && w > 0) {
            return w;
        }
        return modelSceneResolver.contextWindowFor(model);
    }

    /** 用窗口 map 整体替换缓存。 */
    public void refresh(Map<String, Integer> newWindows) {
        windows.set(newWindows != null ? Map.copyOf(newWindows) : Map.of());
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
}
