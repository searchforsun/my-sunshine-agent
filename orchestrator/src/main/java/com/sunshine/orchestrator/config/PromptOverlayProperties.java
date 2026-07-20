package com.sunshine.orchestrator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prompt 叠加残留绑定 — 正文已迁 Catalog；仅保留 skill-overlays Nacos 兜底。
 */
@Getter
@Setter
@RefreshScope
@Component
@ConfigurationProperties(prefix = "agent.prompt")
public class PromptOverlayProperties {

    /**
     * @deprecated Catalog {@code mode-overlay.*}；SpawnSubagentTool 遗留读取路径
     */
    @Deprecated
    private Map<String, String> modeOverlays = new LinkedHashMap<>();

    /** 技能叠加（Catalog 优先，Nacos 兜底） */
    private Map<String, String> skillOverlays = new LinkedHashMap<>();
}
