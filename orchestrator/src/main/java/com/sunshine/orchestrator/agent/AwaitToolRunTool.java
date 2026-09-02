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
import com.sunshine.orchestrator.config.VirtualThreadExecutors;

import java.util.ArrayList;
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
        return "等待 background 工具 / 子任务 / Worker 派发的 run 结束，或在观察窗口到期后返回当前状态快照；"
                + "runIds 数组一次批量等待同轮多个 run（共享观察窗口）。已派发的 run 先查 async_status 再决定是否等待。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runId", Map.of("type", "string", "description", "异步 run 句柄（单值，与 runIds 二选一）"));
        props.put("runIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "待等待的异步 run 句柄列表（同轮派发的多个 runId 一次批量等待，共享观察窗口；与 runId 二选一）"));
        props.put("timeout_sec", Map.of(
                "type", "number",
                "description", "可选观察窗口秒数；exec 默认 30/上限 120，spawn 默认 120/上限 200，worker 默认 120/上限 600（按 run 类型夹紧）"));
        return Map.of(
                "type", "object",
                "properties", props);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
                    String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
                    Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
                    Object runId = input.get("runId");
                    Object runIds = input.get("runIds");
                    Object timeoutSec = input.get("timeout_sec");
                    Integer timeout = timeoutSec instanceof Number n ? n.intValue() : null;
                    String text = awaitToolRuns(resolveRunIds(runId, runIds), timeout, toolUseId);
                    return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(text).build());
                })
                .subscribeOn(VirtualThreadExecutors.scheduler());
    }

    /** 单测入口：无 toolUseId 时回退 activeMessageId（单会话）。 */
    String awaitToolRun(String runId, Integer timeoutSec) {
        return awaitToolRun(runId, timeoutSec, null);
    }

    String awaitToolRun(String runId, Integer timeoutSec, String toolUseId) {
        return awaitToolRuns(runId == null ? List.of() : List.of(runId.strip()), timeoutSec, toolUseId);
    }

    /** runIds 数组 + runId 单值合并去重；均空返回空列表（上层报「runId 不能为空」）。 */
    private static List<String> resolveRunIds(Object runId, Object runIds) {
        List<String> ids = new ArrayList<>();
        if (runIds instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && StringUtils.hasText(item.toString())) {
                    ids.add(item.toString().strip());
                }
            }
        }
        if (runId != null && StringUtils.hasText(runId.toString())) {
            ids.add(runId.toString().strip());
        }
        return ids.stream().distinct().toList();
    }

    String awaitToolRuns(List<String> runIds, Integer timeoutSec, String toolUseId) {
        AgentExecutionProperties.React.AsyncTool cfg = asyncToolConfig();
        if (cfg == null || !cfg.isEnabled()) {
            return errorJson("await_tool_run 未启用");
        }
        if (runIds == null || runIds.isEmpty()) {
            return errorJson("runId 不能为空");
        }
        // v17.12：await 资格 = runId 作用域。runId 为 UUID 随机句柄（派发方上下文才可见），
        // MAIN / WORKER / PLANNER 均可 await 自己派发的 exec / spawn / worker run；
        // 不再要求「仅主 Agent」——Worker 场景（await 自己派发的 exec/spawn）此前被误拒，
        // 也不按 bridge 前缀拒绝 sub（普通 SUB 未注册该工具，天然不会调用）。
        int timeout = resolveTimeoutSec(timeoutSec);
        if (runIds.size() == 1) {
            AsyncToolRunRegistry.Snapshot snapshot = asyncRegistry.await(runIds.get(0), timeout);
            if (snapshot == null) {
                return errorJson("未知 runId");
            }
            return formatSnapshot(snapshot);
        }
        List<AsyncToolRunRegistry.Snapshot> snapshots = asyncRegistry.awaitMany(runIds, timeout);
        if (snapshots.isEmpty()) {
            return errorJson("未知 runId");
        }
        return formatSnapshots(snapshots);
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
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return errorJson("序列化失败");
        }
    }

    /** 批量等待结果：runs[] 数组，顺序与入参一致；未终态含 status=running 无 result。 */
    static String formatSnapshots(List<AsyncToolRunRegistry.Snapshot> snapshots) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("runs", snapshots.stream().map(snapshot -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("runId", snapshot.runId());
            item.put("status", statusToJson(snapshot.status()));
            item.put("waitCount", snapshot.waitCount());
            item.put("waitBudget", snapshot.waitBudget());
            item.put("elapsedMs", snapshot.elapsedMs());
            if (StringUtils.hasText(snapshot.result())) {
                item.put("result", snapshot.result());
            }
            if (StringUtils.hasText(snapshot.partial())) {
                item.put("partial", snapshot.partial());
            }
            return item;
        }).toList());
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
