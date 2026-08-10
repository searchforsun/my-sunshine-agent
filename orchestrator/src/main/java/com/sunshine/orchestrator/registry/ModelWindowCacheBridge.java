package com.sunshine.orchestrator.registry;

import com.sunshine.orchestrator.context.ModelWindowCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Catalog 热更新后把窗口同步进 ModelWindowCache */
@Component
@RequiredArgsConstructor
public class ModelWindowCacheBridge {

    private final ModelSceneResolver modelSceneResolver;
    private final ModelWindowCache modelWindowCache;

    public void syncFromResolver() {
        modelWindowCache.refresh(modelSceneResolver.allContextWindows());
    }
}
