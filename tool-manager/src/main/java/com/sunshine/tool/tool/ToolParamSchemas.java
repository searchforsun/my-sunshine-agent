package com.sunshine.tool.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** OpenAI function parameters 片段，供 catalog / AgentTool 复用 */
public final class ToolParamSchemas {

    private ToolParamSchemas() {
    }

    public static Map<String, Object> stringParam(String name, String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("description", description);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(name, prop);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }
}
