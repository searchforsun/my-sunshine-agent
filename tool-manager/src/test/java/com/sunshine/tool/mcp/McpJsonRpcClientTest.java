package com.sunshine.tool.mcp;

import com.sunshine.tool.entity.McpServerEntity;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpJsonRpcClientTest {

    private final McpJsonRpcClient client = new McpJsonRpcClient(WebClient.builder());

    @Test
    void parseTools_extractsNameDescriptionAndSchema() {
        Map<String, Object> response = Map.of(
                "result", Map.of(
                        "tools", List.of(
                                Map.of(
                                        "name", "read_file",
                                        "description", "Read a file",
                                        "inputSchema", Map.of(
                                                "type", "object",
                                                "properties", Map.of("path", Map.of("type", "string")))))));
        List<McpJsonRpcClient.McpToolDescriptor> tools = client.parseTools(response);
        assertThat(tools).hasSize(1);
        assertThat(tools.getFirst().name()).isEqualTo("read_file");
        assertThat(tools.getFirst().description()).isEqualTo("Read a file");
        assertThat(tools.getFirst().inputSchema()).containsKey("properties");
    }

    @Test
    void parseTools_errorResponseThrows() {
        Map<String, Object> response = Map.of("error", Map.of("message", "failed"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.parseTools(response))
                .isInstanceOf(McpTransportException.class);
    }

    @Test
    void extractCallResult_joinsTextBlocks() {
        Map<String, Object> response = Map.of(
                "result", Map.of(
                        "content", List.of(
                                Map.of("type", "text", "text", "line1"),
                                Map.of("type", "text", "text", "line2"))));
        assertThat(client.extractCallResult(response)).isEqualTo("line1\nline2");
    }

    @Test
    void extractCallResult_errorFlagThrows() {
        Map<String, Object> response = Map.of(
                "result", Map.of("isError", true, "content", List.of()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.extractCallResult(response))
                .isInstanceOf(McpTransportException.class);
    }

    @Test
    @Disabled("requires npx and @modelcontextprotocol/server-filesystem")
    void stdio_listTools_live() {
        McpServerEntity server = new McpServerEntity();
        server.setTransport("stdio");
        server.setCommand("npx");
        server.setArgsJson("[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"/tmp\"]");
        List<McpJsonRpcClient.McpToolDescriptor> tools = client.listTools(server, Duration.ofSeconds(30));
        assertThat(tools).isNotEmpty();
    }
}
