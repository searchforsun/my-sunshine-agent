package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.taskboard.ReactTaskBoardApplyResult;
import com.sunshine.orchestrator.taskboard.ReactTaskBoardService;
import com.sunshine.orchestrator.taskboard.TaskBoardItemInput;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ReAct 元工具 — 维护会话任务清单，不占 tool-manager Catalog */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManageTasksTool {

    public static final String NAME = "manage_tasks";

    private final ReactTaskBoardService taskBoardService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(name = NAME,
            description = "任务清单：规划阶段 merge=false 建板一次；执行中 merge=true 仅更新已有条目 status（带 id）。")
    public String manageTasks(
            @ToolParam(name = "merge", description = "false=首次建板；true=按 id 仅更新已有条目 status")
            boolean merge,
            @ToolParam(name = "items", description = "任务项 JSON 数组，每项含 content、status，可选 id")
            String itemsJson) {
        String messageId = StepEventBridge.activeMessageId();
        if (!StringUtils.hasText(messageId)) {
            return errorJson("无法定位当前会话消息");
        }
        List<TaskBoardItemInput> inputs;
        Set<String> forbidden = new LinkedHashSet<>();
        try {
            List<Map<String, Object>> rawItems = objectMapper.readValue(
                    itemsJson != null ? itemsJson : "[]", new TypeReference<>() {});
            inputs = new ArrayList<>();
            for (Map<String, Object> raw : rawItems) {
                forbidden.addAll(ReactTaskBoardService.forbiddenFields(raw));
                inputs.add(new TaskBoardItemInput(
                        stringField(raw, "id"),
                        stringField(raw, "content"),
                        stringField(raw, "status")));
            }
        } catch (Exception e) {
            log.warn("[ManageTasksTool] items 解析失败: {}", e.getMessage());
            return errorJson("items 必须是合法 JSON 数组");
        }
        if (!forbidden.isEmpty()) {
            return errorJson("任务清单不支持 DAG 字段（" + String.join(", ", forbidden) + "），请改用 plan-workflow");
        }
        ReactTaskBoardApplyResult result = taskBoardService.apply(
                messageId, merge, inputs, List.of());
        if (!result.ok()) {
            return errorJson(result.error());
        }
        String bridgeId = StepEventBridge.activeMainBridge(messageId);
        if (bridgeId != null) {
            StepEventBridge.emit(bridgeId, session -> taskBoardService.emitTimelineUpdate(session, result));
        }
        return successJson(result.revision(), result.summary());
    }

    private static String stringField(Map<String, Object> raw, String key) {
        if (raw == null || !raw.containsKey(key) || raw.get(key) == null) {
            return null;
        }
        return String.valueOf(raw.get(key));
    }

    private static String successJson(int revision, String summary) {
        return "{\"ok\":true,\"revision\":" + revision + ",\"summary\":\"" + escape(summary) + "\"}";
    }

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
