package com.sunshine.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.tool.entity.McpServerEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP JSON-RPC 客户端：initialize / tools/list / tools/call */
@Slf4j
@Component
public class McpJsonRpcClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final WebClient webClient;

    public McpJsonRpcClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    public List<McpToolDescriptor> listTools(McpServerEntity server, Duration timeout) {
        try (McpTransport transport = openTransport(server, timeout)) {
            initialize(transport);
            Map<String, Object> response = transport.sendRequest("tools/list", Map.of(), 2);
            return parseTools(response);
        }
    }

    public String toolsCall(McpServerEntity server, String toolName, Map<String, String> params, Duration timeout) {
        try (McpTransport transport = openTransport(server, timeout)) {
            initialize(transport);
            Map<String, Object> arguments = params != null ? new LinkedHashMap<>(params) : Map.of();
            Map<String, Object> callParams = Map.of(
                    "name", toolName,
                    "arguments", arguments);
            Map<String, Object> response = transport.sendRequest("tools/call", callParams, 3);
            return extractCallResult(response);
        }
    }

    McpTransport openTransport(McpServerEntity server, Duration timeout) {
        String transport = server.getTransport();
        if ("stdio".equalsIgnoreCase(transport)) {
            try {
                return new McpStdioTransport(
                        server.getCommand(),
                        parseStringList(server.getArgsJson()),
                        parseStringMap(server.getEnvJson()));
            } catch (java.io.IOException e) {
                throw new McpTransportException("stdio transport failed", e);
            }
        }
        if ("sse".equalsIgnoreCase(transport)) {
            if (!StringUtils.hasText(server.getEndpoint())) {
                throw new McpTransportException("MCP SSE endpoint required");
            }
            return new McpSseTransport(webClient, server.getEndpoint(), timeout);
        }
        throw new McpTransportException("unsupported transport: " + transport);
    }

    private void initialize(McpTransport transport) {
        Map<String, Object> clientInfo = Map.of(
                "name", "sunshine-tool-service",
                "version", "1.0.0");
        Map<String, Object> params = Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", clientInfo);
        Map<String, Object> response = transport.sendRequest("initialize", params, 1);
        if (response.containsKey("error")) {
            throw new McpTransportException("initialize failed: " + response.get("error"));
        }
        if (transport instanceof McpStdioTransport stdio) {
            stdio.sendNotification("notifications/initialized");
        }
    }

    @SuppressWarnings("unchecked")
    List<McpToolDescriptor> parseTools(Map<String, Object> response) {
        if (response.containsKey("error")) {
            throw new McpTransportException("tools/list failed: " + response.get("error"));
        }
        Object resultObj = response.get("result");
        if (!(resultObj instanceof Map<?, ?> result)) {
            return List.of();
        }
        Object toolsObj = result.get("tools");
        if (!(toolsObj instanceof List<?> tools)) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (Object item : tools) {
            if (!(item instanceof Map<?, ?> tool)) {
                continue;
            }
            String name = stringValue(tool.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            String description = stringValue(tool.get("description"));
            Map<String, Object> inputSchema = extractInputSchema(tool);
            descriptors.add(new McpToolDescriptor(name, description, inputSchema));
        }
        return descriptors;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractInputSchema(Map<?, ?> tool) {
        Object schema = tool.get("inputSchema");
        if (schema == null) {
            schema = tool.get("input_schema");
        }
        if (schema instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of("type", "object", "properties", Map.of());
    }

    @SuppressWarnings("unchecked")
    String extractCallResult(Map<String, Object> response) {
        if (response.containsKey("error")) {
            throw new McpTransportException("tools/call failed: " + response.get("error"));
        }
        Object resultObj = response.get("result");
        if (!(resultObj instanceof Map<?, ?> result)) {
            return "";
        }
        if (Boolean.TRUE.equals(result.get("isError"))) {
            throw new McpTransportException("tools/call error: " + result);
        }
        Object contentObj = result.get("content");
        if (!(contentObj instanceof List<?> contents)) {
            return stringify(result.get("structuredContent"));
        }
        StringBuilder sb = new StringBuilder();
        for (Object item : contents) {
            if (!(item instanceof Map<?, ?> block)) {
                continue;
            }
            if ("text".equals(block.get("type"))) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(stringValue(block.get("text")));
            }
        }
        return sb.toString();
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[McpJsonRpcClient] invalid args_json: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, String> parseStringMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[McpJsonRpcClient] invalid env_json: {}", e.getMessage());
            return Map.of();
        }
    }

    public record McpToolDescriptor(String name, String description, Map<String, Object> inputSchema) {
    }
}
