package com.sunshine.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** MCP HTTP/SSE 传输：WebClient POST JSON-RPC */
public class McpSseTransport implements McpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final String endpoint;
    private final Duration timeout;

    public McpSseTransport(WebClient webClient, String endpoint, Duration timeout) {
        this.webClient = webClient;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override
    public Map<String, Object> sendRequest(String method, Map<String, Object> params, int id) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        String body = webClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .block();
        if (body == null || body.isBlank()) {
            throw new McpTransportException("empty HTTP response for " + method);
        }
        try {
            return MAPPER.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            throw new McpTransportException("invalid JSON response for " + method, e);
        }
    }

    @Override
    public void close() {
        // HTTP 无持久连接
    }
}
