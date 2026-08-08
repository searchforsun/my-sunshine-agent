package com.sunshine.tool.invoke;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.tool.config.ToolIntegrationProperties;
import com.sunshine.tool.entity.McpServerEntity;
import com.sunshine.tool.entity.ToolDefinitionEntity;
import com.sunshine.tool.exception.ToolErrorCode;
import com.sunshine.tool.mcp.McpJsonRpcClient;
import com.sunshine.tool.repo.McpServerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpInvokeExecutorTest {

    @Mock
    private McpServerRepository mcpServerRepository;
    @Mock
    private McpJsonRpcClient mcpJsonRpcClient;

    @Test
    void invoke_delegatesToJsonRpcClient() {
        McpInvokeExecutor executor = buildExecutor();
        McpServerEntity server = new McpServerEntity();
        server.setId("demo");
        server.setEnabled(true);
        when(mcpServerRepository.findById("demo")).thenReturn(Optional.of(server));
        when(mcpJsonRpcClient.toolsCall(eq(server), eq("read_file"), any(), any(Duration.class)))
                .thenReturn("file contents");

        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setId("mcp__demo__read_file");
        tool.setSourceRef("demo");
        tool.setExternalName("read_file");

        assertThat(executor.invoke(tool, Map.of("path", "/tmp/a.txt"))).isEqualTo("file contents");
    }

    @Test
    void invoke_disabledServerThrows() {
        McpInvokeExecutor executor = buildExecutor();
        McpServerEntity server = new McpServerEntity();
        server.setId("demo");
        server.setEnabled(false);
        when(mcpServerRepository.findById("demo")).thenReturn(Optional.of(server));

        ToolDefinitionEntity tool = new ToolDefinitionEntity();
        tool.setSourceRef("demo");
        tool.setExternalName("read_file");

        assertThatThrownBy(() -> executor.invoke(tool, Map.of()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ToolErrorCode.MCP_SERVER_DISABLED));
    }

    private McpInvokeExecutor buildExecutor() {
        ToolIntegrationProperties properties = new ToolIntegrationProperties();
        properties.getMcp().setInvokeTimeoutSeconds(5);
        return new McpInvokeExecutor(mcpServerRepository, mcpJsonRpcClient, properties);
    }
}
