package com.sunshine.tool.mcp;

import com.sunshine.tool.entity.McpServerEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpImportServiceTest {

    private final McpImportService importService = new McpImportService();

    @Test
    void parse_cursorStdioFormat() {
        String json = """
                {"mcpServers":{"demo":{"command":"echo","args":["mcp"]}}}
                """;
        List<McpServerEntity> servers = importService.parse(json);
        assertThat(servers).hasSize(1);
        McpServerEntity server = servers.getFirst();
        assertThat(server.getId()).isEqualTo("demo");
        assertThat(server.getTransport()).isEqualTo("stdio");
        assertThat(server.getCommand()).isEqualTo("echo");
        assertThat(server.getArgsJson()).contains("mcp");
    }

    @Test
    void parse_cursorSseFormat() {
        String json = """
                {"mcpServers":{"remote":{"url":"http://localhost:8080/mcp"}}}
                """;
        List<McpServerEntity> servers = importService.parse(json);
        assertThat(servers).hasSize(1);
        assertThat(servers.getFirst().getTransport()).isEqualTo("sse");
        assertThat(servers.getFirst().getEndpoint()).isEqualTo("http://localhost:8080/mcp");
    }

    @Test
    void export_roundTrip() {
        McpServerEntity server = new McpServerEntity();
        server.setId("demo");
        server.setTransport("stdio");
        server.setCommand("echo");
        server.setArgsJson("[\"mcp\"]");
        String exported = importService.export(List.of(server));
        assertThat(exported).contains("\"demo\"");
        assertThat(exported).contains("\"command\" : \"echo\"");
        assertThat(importService.parse(exported)).hasSize(1);
    }
}
