package com.sunshine.workflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workflow Studio 可配置项 — SSOT：Nacos sunshine-workflow-manager.yaml */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "workflow")
public class WorkflowStudioProperties {

    private NodeDefaults nodeDefaults = new NodeDefaults();
    private CatalogDefaults catalogDefaults = new CatalogDefaults();
    private Map<String, NodeParamDefaults> nodeParamDefaults = defaultNodeParamDefaults();

    @Getter
    @Setter
    public static class CatalogDefaults {

        private String intentAfter = "将按「{displayName}」流程处理";
    }

    @Getter
    @Setter
    public static class NodeParamDefaults {

        private Integer topK;
        private String kbIdEmptyLabel;
        private Integer maxIters;
    }

    private static Map<String, NodeParamDefaults> defaultNodeParamDefaults() {
        Map<String, NodeParamDefaults> map = new LinkedHashMap<>();
        NodeParamDefaults rag = new NodeParamDefaults();
        rag.setTopK(3);
        rag.setKbIdEmptyLabel("（会话默认）");
        map.put("rag", rag);
        NodeParamDefaults agent = new NodeParamDefaults();
        agent.setMaxIters(8);
        agent.setKbIdEmptyLabel("（会话默认）");
        map.put("agent", agent);
        return map;
    }

    @Getter
    @Setter
    public static class NodeDefaults {

        private RetryDefaults defaults = new RetryDefaults();
        private String criticalOnFailure = "fail_fast";
        private Map<String, TypeRetryDefaults> byType = defaultByType();

        private static Map<String, TypeRetryDefaults> defaultByType() {
            Map<String, TypeRetryDefaults> map = new LinkedHashMap<>();
            map.put("rag", typeOverride(1, null, null));
            map.put("tool", typeOverride(2, null, null));
            map.put("agent", typeOverride(1, null, null));
            map.put("answer", typeOverride(2, null, "fail_fast"));
            map.put("join", typeOverride(2, null, null));
            map.put("llm", typeOverride(2, null, null));
            return map;
        }

        private static TypeRetryDefaults typeOverride(Integer maxAttempts, Long backoffMs, String onFailure) {
            TypeRetryDefaults t = new TypeRetryDefaults();
            t.setMaxAttempts(maxAttempts);
            t.setBackoffMs(backoffMs);
            t.setOnFailure(onFailure);
            return t;
        }
    }

    @Getter
    @Setter
    public static class RetryDefaults {

        private int maxAttempts = 2;
        private long backoffMs = 500;
        private double backoffMultiplier = 2.0;
        private String onFailure = "continue";
        private List<String> retryOnErrorClass = new ArrayList<>(List.of(
                "TIMEOUT", "SERVICE_UNAVAILABLE", "CIRCUIT_OPEN"));
    }

    @Getter
    @Setter
    public static class TypeRetryDefaults {

        private Integer maxAttempts;
        private Long backoffMs;
        private String onFailure;
    }
}
