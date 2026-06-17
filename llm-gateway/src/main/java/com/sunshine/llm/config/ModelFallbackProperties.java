package com.sunshine.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主模型失败时的降级映射，例如 deepseek-v4-pro → qwen-plus
 */
@Data
@Component
@ConfigurationProperties(prefix = "llm.fallback")
public class ModelFallbackProperties {

    /** model → fallback model */
    private Map<String, String> routes = defaultRoutes();

    private static Map<String, String> defaultRoutes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("deepseek-v4-pro", "qwen-plus");
        m.put("deepseek-v4-flash", "qwen-plus");
        return m;
    }

    public String fallbackFor(String model) {
        if (model == null || routes == null) {
            return null;
        }
        return routes.get(model);
    }
}
