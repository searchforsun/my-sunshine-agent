package com.sunshine.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.tool.entity.McpServerEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 解析 Cursor 兼容 mcp.json 格式 */
@Service
@RequiredArgsConstructor
public class McpImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<McpServerEntity> parse(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            Map<String, Object> root = MAPPER.readValue(rawJson, new TypeReference<>() {});
            Object serversObj = root.get("mcpServers");
            if (!(serversObj instanceof Map<?, ?> servers)) {
                return List.of();
            }
            List<McpServerEntity> result = new ArrayList<>();
            for (Map.Entry<?, ?> entry : servers.entrySet()) {
                String id = entry.getKey().toString();
                if (!(entry.getValue() instanceof Map<?, ?> config)) {
                    continue;
                }
                result.add(toEntity(id, config));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid mcp.json: " + e.getMessage(), e);
        }
    }

    public String export(List<McpServerEntity> servers) {
        Map<String, Object> mcpServers = new LinkedHashMap<>();
        for (McpServerEntity server : servers) {
            mcpServers.put(server.getId(), toConfig(server));
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("mcpServers", mcpServers));
        } catch (Exception e) {
            throw new IllegalStateException("export failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private McpServerEntity toEntity(String id, Map<?, ?> config) {
        McpServerEntity entity = new McpServerEntity();
        entity.setId(id);
        entity.setDisplayName(id);
        Object url = config.get("url");
        if (url != null && StringUtils.hasText(url.toString())) {
            entity.setTransport("sse");
            entity.setEndpoint(url.toString());
        } else {
            entity.setTransport("stdio");
            entity.setCommand(stringValue(config.get("command")));
            entity.setArgsJson(writeJson(parseStringList(config.get("args"))));
            entity.setEnvJson(writeJson(parseStringMap(config.get("env"))));
        }
        if (!StringUtils.hasText(entity.getTenantId())) {
            entity.setTenantId("default");
        }
        return entity;
    }

    private Map<String, Object> toConfig(McpServerEntity server) {
        Map<String, Object> config = new LinkedHashMap<>();
        if ("sse".equalsIgnoreCase(server.getTransport())) {
            config.put("url", server.getEndpoint());
            return config;
        }
        config.put("command", server.getCommand());
        config.put("args", parseJsonList(server.getArgsJson()));
        Map<String, String> env = parseJsonMap(server.getEnvJson());
        if (!env.isEmpty()) {
            config.put("env", env);
        }
        return config;
    }

    private List<String> parseStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of(value.toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue() == null ? "" : entry.getValue().toString());
        }
        return result;
    }

    private List<String> parseJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, String> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }
}
