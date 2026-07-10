package com.sunshine.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP stdio 传输：子进程 stdin/stdout 按行 JSON-RPC */
@Slf4j
public class McpStdioTransport implements McpTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;

    public McpStdioTransport(String command, List<String> args, Map<String, String> env) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(buildCommand(command, args));
        builder.environment().putAll(env != null ? env : Map.of());
        builder.redirectErrorStream(true);
        process = builder.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    private List<String> buildCommand(String command, List<String> args) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(command);
        if (args != null) {
            cmd.addAll(args);
        }
        return cmd;
    }

    @Override
    public Map<String, Object> sendRequest(String method, Map<String, Object> params, int id) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null) {
                request.put("params", params);
            }
            String line = MAPPER.writeValueAsString(request);
            writer.write(line);
            writer.newLine();
            writer.flush();
            return readResponse(id);
        } catch (IOException e) {
            throw new McpTransportException("stdio request failed: " + method, e);
        }
    }

    /** MCP 通知（无 id） */
    public void sendNotification(String method) {
        try {
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            writer.write(MAPPER.writeValueAsString(notification));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new McpTransportException("stdio notification failed: " + method, e);
        }
    }

    private Map<String, Object> readResponse(int expectedId) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> message;
            try {
                message = MAPPER.readValue(line, new TypeReference<>() {});
            } catch (Exception e) {
                // 部分 MCP 进程（如 server-memory）会在 stdout 打印非 JSON 启动日志
                log.debug("[McpStdioTransport] skip non-JSON line: {}", line);
                continue;
            }
            if (message.containsKey("method")) {
                continue;
            }
            Object id = message.get("id");
            if (id instanceof Number number && number.intValue() == expectedId) {
                return message;
            }
        }
        throw new McpTransportException("no response for id=" + expectedId);
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        try {
            reader.close();
        } catch (IOException ignored) {
        }
        process.destroyForcibly();
    }
}
