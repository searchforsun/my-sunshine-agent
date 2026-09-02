package com.sunshine.orchestrator.plan.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.taskboard.TaskBoardItemView;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Planner 元工具组 — {@code plan_submit}（提交调度单元）+ {@code self_assess}（汇报进度决策）+ {@code task_status}（查询任务状态）。
 * <p>
 * 架构决议：Planner 动作协议从「文本 JSON」改为 AgentScope 原生工具调用（与 dispatch_worker 同构），
 * 参数由框架序列化保证合法，消除「模型手写非法 JSON」整类故障。工具直接操作 {@code DispatchSession.notebook}。
 */
@Component
public class PlannerActionTool {

    public static final String PLAN_TOOL = "plan_submit";
    public static final String ASSESS_TOOL = "self_assess";
    public static final String STATUS_TOOL = "task_status";

    /** taskBoard 快照 revision：plan 提交与 Worker 完成单调递增 */
    private static final AtomicInteger TASK_BOARD_REVISION = new AtomicInteger(0);

    /** 注册钩子：仅 PLANNER toolkit 调用（对齐 {@link WorkerDispatchTool#registerIntoPlannerToolkit}）。 */
    public void registerIntoPlannerToolkit(Toolkit toolkit) {
        if (toolkit == null) {
            return;
        }
        toolkit.registerAgentTool(new PlanSubmitTool());
        toolkit.registerAgentTool(new SelfAssessTool());
        toolkit.registerAgentTool(new TaskStatusTool());
    }

    private final class PlanSubmitTool implements AgentTool {
        @Override
        public String getName() {
            return PLAN_TOOL;
        }

        @Override
        public String getDescription() {
            return "提交本轮的调度单元集合（覆盖 taskQueue）。需要推进任务时调用；信息不足先排调研/摸底单元；可多次调用，最后一次提交为准。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> taskProps = new LinkedHashMap<>();
            taskProps.put("taskId", Map.of("type", "string", "description", "会话内唯一短 id（如 t1、research-codebase）"));
            taskProps.put("label", Map.of("type", "string", "description", "粗单元标题（里程碑/调研/执行步），不含具体命令或文件路径"));
            taskProps.put("dependsOn", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "前置 taskId 列表；无依赖则 []；有依赖则按波次串行"));
            taskProps.put("constraints", Map.of("type", "string", "description", "约束（可选），写给 Worker 的任务契约"));
            taskProps.put("expectedOutput", Map.of("type", "string", "description", "期望产出（可选）"));
            taskProps.put("successCriteria", Map.of("type", "string", "description", "成功标准（可选）"));
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("reason", Map.of("type", "string", "description", "本轮规划意图（≤40字）"));
            props.put("tasks", Map.of("type", "array",
                    "items", Map.of("type", "object", "properties", taskProps, "required", List.of("taskId", "label")),
                    "description", "本波调度单元列表；同波无互相 dependsOn 的单元可并行"));
            return Map.of("type", "object", "properties", props, "required", List.of("tasks"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
                Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
                WorkerDispatchTool.DispatchSession session = WorkerDispatchTool.resolveSessionForToolUse(toolUseId);
                String text = submitPlan(session, input.get("tasks"));
                return ToolResultBlock.of(toolUseId, PLAN_TOOL, TextBlock.builder().text(text).build());
            });
        }
    }

    private final class SelfAssessTool implements AgentTool {
        @Override
        public String getName() {
            return ASSESS_TOOL;
        }

        @Override
        public String getDescription() {
            return "评估本轮进度并向引擎汇报决策。每轮 Worker 批次结束后调用一次；nextDirection=continue 继续下一波，replan 需重新 plan_submit，answer 进入综合回答。";
        }

        @Override
        public Map<String, Object> getParameters() {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("goalCompletion", Map.of("type", "number", "description", "0~1，离 originalGoal 的完成度"));
            props.put("nextDirection", Map.of("type", "string",
                    "description", "continue|replan|answer：continue=继续调度下一波 Worker；replan=需更新 taskQueue（再调 plan_submit）；answer=信息已足，进入综合回答"));
            props.put("reason", Map.of("type", "string", "description", "评估依据（≤60字）"));
            return Map.of("type", "object", "properties", props, "required", List.of("goalCompletion", "nextDirection"));
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
                Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
                WorkerDispatchTool.DispatchSession session = WorkerDispatchTool.resolveSessionForToolUse(toolUseId);
                String text = submitAssess(session, input.get("goalCompletion"), input.get("nextDirection"), input.get("reason"));
                return ToolResultBlock.of(toolUseId, ASSESS_TOOL, TextBlock.builder().text(text).build());
            });
        }
    }

    private final class TaskStatusTool implements AgentTool {
        @Override
        public String getName() {
            return STATUS_TOOL;
        }

        @Override
        public String getDescription() {
            return "查询当前所有任务的执行状态元数据（含执行单元 id/标签/状态/重试版本/失败原因/依赖）。await_tool_run 超时或需要决策是否重派时调用，据此判断待办、运行中、成功、失败、取消的任务，并决定重派（同任务 t1-2/t1-3）或换任务。";
        }

        @Override
        public Map<String, Object> getParameters() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                String toolUseId = param.getToolUseBlock() != null ? param.getToolUseBlock().getId() : null;
                WorkerDispatchTool.DispatchSession session = WorkerDispatchTool.resolveSessionForToolUse(toolUseId);
                String text = submitTaskStatus(session);
                return ToolResultBlock.of(toolUseId, STATUS_TOOL, TextBlock.builder().text(text).build());
            });
        }
    }

    /** 引擎侧同步入口（工具 callAsync / 单测复用）：输出任务队列状态 JSON（供 Planner 决策）。 */
    String submitTaskStatus(WorkerDispatchTool.DispatchSession session) {
        if (session == null || session.notebook() == null) {
            return errorJson("未绑定 WorkerDispatch 会话（须先 bindSession）");
        }
        List<TaskItem> queue = session.notebook().snapshotQueue();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"tasks\":[");
        for (int i = 0; i < queue.size(); i++) {
            TaskItem t = queue.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"taskId\":\"").append(escape(t.taskId()))
                    .append("\",\"label\":\"").append(escape(t.label()))
                    .append("\",\"status\":\"").append(escape(t.status()))
                    .append("\",\"retryIndex\":").append(t.retryIndex())
                    .append(",\"failReason\":\"").append(escape(t.failReason() == null ? "" : t.failReason()))
                    .append("\",\"dependsOn\":").append(jsonArray(t.dependsOn()))
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(values.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    /** 引擎侧同步入口（工具 callAsync / 单测复用）：解析 tasks 并覆盖 notebook.taskQueue。 */
    String submitPlan(WorkerDispatchTool.DispatchSession session, Object rawTasks) {
        if (session == null || session.notebook() == null) {
            return errorJson("未绑定 WorkerDispatch 会话（须先 bindSession）");
        }
        List<TaskItem> tasks = parseTasks(rawTasks);
        if (tasks == null) {
            return errorJson("tasks 参数结构非法（须为对象数组，每项含 taskId/label）");
        }
        HarnessPlanner.mergeTaskQueue(session.notebook(), tasks);
        // 规划落定即实时下发 taskBoard 快照（此时 planner bridge 存活，token 才能直达前端）
        emitTaskBoardSnapshot(session, "plan");
        return "{\"ok\":true,\"scheduled\":" + tasks.size() + "}";
    }

    /** Worker 完成后的进度快照：由 WorkerDispatchTool 回调（bridge 存活期）。 */
    void emitTaskBoardSnapshot(WorkerDispatchTool.DispatchSession session, String source) {
        if (session == null || session.notebook() == null) {
            return;
        }
        String bridgeId = session.plannerBridgeId();
        if (!StringUtils.hasText(bridgeId) || !StepEventBridge.hasSession(bridgeId)) {
            return;
        }
        int revision = TASK_BOARD_REVISION.incrementAndGet();
        List<TaskBoardItemView> items = HarnessTaskBoardProjector.project(session.notebook());
        long done = session.notebook().snapshotQueue().stream()
                .filter(t -> "done".equals(t.status())).count();
        // harness H1：下发 taskQueue 字段（前端据此判定 harness 看板并加 T1-1 执行单元记号）
        StepMetadata meta = StepMetadata.withTaskQueue(items, revision, done + "/" + items.size());
        StepEventBridge.emit(bridgeId, s -> s.enqueueAuxiliary(
                StreamToken.step(ProcessingStep.done("tasks", "tasks", "任务看板", null)
                        .withMetadata(meta))));
    }

    /** 引擎侧同步入口（工具 callAsync / 单测复用）：写入完成度与决策方向。 */
    String submitAssess(WorkerDispatchTool.DispatchSession session, Object rawCompletion, Object nextDirection, Object reason) {
        if (session == null || session.notebook() == null) {
            return errorJson("未绑定 WorkerDispatch 会话（须先 bindSession）");
        }
        if (!(rawCompletion instanceof Number n)) {
            return errorJson("goalCompletion 必须为数字");
        }
        PlanNotebook notebook = session.notebook();
        double completion = Math.min(1.0, Math.max(0.0, n.doubleValue()));
        notebook.setGoalCompletion(completion);
        String direction = textValue(nextDirection);
        notebook.setNextDirection(StringUtils.hasText(direction) ? direction.strip() : null);
        return "{\"ok\":true,\"goalCompletion\":" + completion + "}";
    }

    /** 解析工具参数 tasks（List<Map> 或 List<JsonNode>）；结构非法返回 null。 */
    static List<TaskItem> parseTasks(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<TaskItem> items = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> m)) {
                return null;
            }
            String taskId = textValue(m.get("taskId")).strip();
            String label = textValue(m.get("label")).strip();
            if (!StringUtils.hasText(taskId) || !StringUtils.hasText(label)) {
                return null;
            }
            items.add(new TaskItem(
                    taskId,
                    label,
                    "pending",
                    readDependsOn(m.get("dependsOn")),
                    textValue(m.get("constraints")).strip(),
                    textValue(m.get("expectedOutput")).strip(),
                    textValue(m.get("successCriteria")).strip(),
                    TaskItem.stripRetrySuffix(taskId),
                    1,
                    null,
                    null));
        }
        return items;
    }

    private static List<String> readDependsOn(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object dep : list) {
            String id = textValue(dep).strip();
            if (StringUtils.hasText(id)) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }

    private static String textValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof JsonNode node) {
            return node.asText();
        }
        return String.valueOf(value);
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
