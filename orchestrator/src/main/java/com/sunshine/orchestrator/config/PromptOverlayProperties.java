package com.sunshine.orchestrator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PromptComposer 模式/技能/作答边界叠加 — SSOT 见 Nacos {@code agent.prompt.*}。
 */
@Getter
@Setter
@RefreshScope
@Component
@ConfigurationProperties(prefix = "agent.prompt")
public class PromptOverlayProperties {

    /** 模式叠加：simple-llm / react / workflow / workflow:{id} */
    private Map<String, String> modeOverlays = new LinkedHashMap<>();

    /** 技能叠加（3.11 前可空） */
    private Map<String, String> skillOverlays = new LinkedHashMap<>();

    /** 作答边界 — 注入在 memory 层之后、当前提问之前 */
    private String scopePrompt = "";

    /** 动态 Plan answer 节点 prompt 模板（占位符 {{start.userQuery}} / {{plan.upstream}}） */
    private String answerTemplate = "";

    /** 终态 answer 节点叠加（拼在 nodePrompt 前；reasoning 由引擎丢弃） */
    private String answerOverlay = "";
}
