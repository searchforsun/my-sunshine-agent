package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ReAct 元工具 — 查询异步 run 状态（peek 语义，不阻塞、不消耗 await 预算）。
 * MAIN / WORKER / PLANNER 通用：background exec、spawn_subagent、dispatch_worker 派发的 run 均可查。
 */
@Component
@RequiredArgsConstructor
public class AsyncStatusTool implements AgentTool {

    public static final String NAME = "async_status";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsyncToolRunRegistry asyncRegistry;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "查询后台异步 run 的当前状态元数据（background exec / spawn_subagent / dispatch_worker 派发的 run）；"
                + "立即返回不等待，不消耗 await 预算；await 超时或怀疑异常时先查本工具再决定继续等、重派或收束。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("runId", Map.of("type", "string", "description", "异步 run 句柄（单值，与 runIds 二选一）"));
        props.put("runIds", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "待查询的异步 run 句柄列表（一次查询多个，与 runId 二选一）"));
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
                    String text = queryStatus(resolveRunIds(runId, runIds));
                    return ToolResultBlock.of(toolUseId, NAME, TextBlock.builder().text(text).build());
                })
                .subscribeOn(VirtualThreadExecutors.scheduler());
    }

    /** 单测入口 */
    String queryStatus(String runId) {
        return queryStatus(runId == null ? List.of() : List.of(runId.strip()));
    }

    /** runIds 数组 + runId 单值合并去重；均空返回错误。 */
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

    String queryStatus(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return errorJson("runId 不能为空");
        }
        List<AsyncToolRunRegistry.Snapshot> snapshots = runIds.stream()
                .map(asyncRegistry::peek)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (snapshots.isEmpty()) {
            return errorJson("未知 runId");
        }
        if (snapshots.size() == 1 && runIds.size() == 1) {
            return formatSnapshot(snapshots.get(0));
        }
        return formatSnapshots(snapshots);
    }

    static String formatSnapshot(AsyncToolRunRegistry.Snapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("runId", snapshot.runId());
        map.put("kind", kindToJson(snapshot.kind()));
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
        return write(map);
    }

    /** 批量查询结果：runs[] 数组，顺序与入参一致；未知 runId 跳过。 */
    static String formatSnapshots(List<AsyncToolRunRegistry.Snapshot> snapshots) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        map.put("runs", snapshots.stream().map(snapshot -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("runId", snapshot.runId());
            item.put("kind", kindToJson(snapshot.kind()));
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
        return write(map);
    }

    private static String write(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return errorJson("序列化失败");
        }
    }

    private static String kindToJson(AsyncToolRunRegistry.Kind kind) {
        if (kind == null) {
            return "unknown";
        }
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static String statusToJson(AsyncToolRunRegistry.Status status) {
        if (status == null) {
            return "running";
        }
        return status.name().toLowerCase(Locale.ROOT);
    }

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
