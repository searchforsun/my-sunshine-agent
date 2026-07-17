package com.sunshine.orchestrator.sandbox;

import com.sunshine.orchestrator.config.AgentSandboxProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 沙箱工具 JSON Schema 构建 — 参数定义 SSOT：Nacos agent.sandbox.tools */
public final class SandboxToolSchemas {

    private SandboxToolSchemas() {}

    public static Map<String, Object> toParameters(AgentSandboxProperties.ToolDef def) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (def.getProperties() != null) {
            def.getProperties().forEach((name, param) -> {
                Map<String, Object> prop = new LinkedHashMap<>();
                if (param.getType() != null && !param.getType().isBlank()) {
                    prop.put("type", param.getType());
                }
                if (param.getDescription() != null && !param.getDescription().isBlank()) {
                    prop.put("description", param.getDescription());
                }
                props.put(name, prop);
            });
        }
        List<String> required = def.getRequired() != null ? new ArrayList<>(def.getRequired()) : List.of();
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        return schema;
    }
}
