package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ReAct 元工具 — 主 Agent 分片等待/窥视异步长工具 run 状态（exec/spawn background）。
 */
@Component
@RequiredArgsConstructor
public class AwaitToolRunTool implements AgentTool {

    public static final String NAME = "await_tool_run";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentExecutionProperties executionProperties;
    private final AsyncToolRunRegistry asyncRegistry;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "等待 background 工具或子任务结束，或在观察窗口到期后返回当前状态快照。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runId", Map.of("type", "string", "description", "异步 run 句柄（必填）"));
        props.put("timeout_sec", Map.of(
                "type", "number",
                "description", "可选观察窗口秒数；exec 默认 30/上限 120，spawn 默认 120/上限 200（按 run 类型夹紧）"));
        return Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("runId"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
                    String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
                    Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
                    Object runId = input.get("runId");
                    Object timeoutSec = input.get("timeout_sec");
                    Integer timeout = timeoutSec instanceof Number n ? n.intValue() : null;
                    String text = awaitToolRun(
                            runId != null ? String.valueOf(runId) : null,
                            timeout,
                            toolUseId);
                    return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(text).build());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 单测入口：无 toolUseId 时回退 activeMessageId（单会话）。 */
    String awaitToolRun(String runId, Integer timeoutSec) {
        return awaitToolRun(runId, timeoutSec, null);
    }

    String awaitToolRun(String runId, Integer timeoutSec, String toolUseId) {
        AgentExecutionProperties.React.AsyncTool cfg = asyncToolConfig();
        if (cfg == null || !cfg.isEnabled()) {
            return errorJson("await_tool_run 未启用");
        }
        if (!StringUtils.hasText(runId)) {
            return errorJson("runId 不能为空");
        }
        String messageId = StepEventBridge.resolveMessageIdForToolUse(toolUseId);
        if (!StringUtils.hasText(messageId)) {
            return errorJson("无法定位当前会话消息");
        }
        String mainBridge = StepEventBridge.activeMainBridge(messageId);
        if (!StringUtils.hasText(mainBridge)) {
            return errorJson("await_tool_run 仅可从主 Agent 调用");
        }
        String activeBridge = StepEventBridge.bridgeIdForToolUse(toolUseId);
        if (!StringUtils.hasText(activeBridge)) {
            activeBridge = StepEventBridge.activeBridgeId();
        }
        if (StringUtils.hasText(activeBridge) && activeBridge.startsWith("sub-")) {
            return errorJson("子 Agent 不可调用 await_tool_run");
        }

        int timeout = resolveTimeoutSec(timeoutSec);
        AsyncToolRunRegistry.Snapshot snapshot = asyncRegistry.await(runId.strip(), timeout);
        if (snapshot == null) {
            return errorJson("未知 runId");
        }
        return formatSnapshot(snapshot);
    }

    private AgentExecutionProperties.React.AsyncTool asyncToolConfig() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null ? react.getAsyncTool() : null;
    }

    /**
     * 仅规范化入参；上限按 run kind 在 {@link AsyncToolRunRegistry} 夹紧。
     * null → 0（registry 用该 kind 默认）；&lt;1 → 1。
     */
    private static int resolveTimeoutSec(Integer timeoutSec) {
        if (timeoutSec == null) {
            return 0;
        }
        return Math.max(1, timeoutSec);
    }

    static String formatSnapshot(AsyncToolRunRegistry.Snapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("runId", snapshot.runId());
        map.put("status", statusToJson(snapshot.status()));
        map.put("waitCount", snapshot.waitCount());
        map.put("waitBudget", snapshot.waitBudget());
        map.put("elapsedMs", snapshot.elapsedMs());
        if (StringUtils.hasText(snapshot.result())) {
            map.put("result", snapshot.result());
        }
        if (StringUtils.hasText(snapshot.partial())) {
            map.put("partial", snapshot.partial());
        }
        if (StringUtils.hasText(snapshot.error())) {
            map.put("error", snapshot.error());
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return errorJson("序列化失败");
        }
    }

    private static String statusToJson(AsyncToolRunRegistry.Status status) {
        if (status == null) {
            return "running";
        }
        return status.name().toLowerCase(Locale.ROOT);
    }

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
