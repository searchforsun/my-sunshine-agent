package com.sunshine.tool.mcp;

import java.io.Closeable;
import java.util.Map;

/** MCP JSON-RPC 传输层 */
public interface McpTransport extends Closeable {

    Map<String, Object> sendRequest(String method, Map<String, Object> params, int id);

    @Override
    void close();
}
