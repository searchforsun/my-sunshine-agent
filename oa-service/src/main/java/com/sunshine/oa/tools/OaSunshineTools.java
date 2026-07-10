package com.sunshine.oa.tools;

import com.sunshine.oa.dto.OaTaskVO;
import com.sunshine.oa.service.OaTaskService;
import com.sunshine.tools.sdk.annotation.SunshineTool;
import com.sunshine.tools.sdk.annotation.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OaSunshineTools {

    private final OaTaskService taskService;

    @SunshineTool(
            id = "list_oa_tasks",
            displayName = "查询 OA 待办",
            description = "查询 OA 待办任务。用户问请假审批、合同会签、出差/用印等待办时使用。",
            outputSummaryKind = "oa-tasks")
    public String listOaTasks(
            @ToolParam(value = "status", description = "pending | done | all", required = false) String status) {
        List<OaTaskVO> tasks = taskService.list(status != null ? status : "pending");
        if (tasks.isEmpty()) {
            return "未查询到符合条件的 OA 待办。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(tasks.size()).append(" 条 OA 待办：\n");
        for (OaTaskVO task : tasks) {
            sb.append("- [").append(task.id()).append("] ")
                    .append(task.title())
                    .append(" | 分类=").append(task.category())
                    .append(" | 状态=").append(task.status())
                    .append(" | 处理人=").append(task.assignee())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    @SunshineTool(
            id = "approve_oa_task",
            displayName = "审批 OA 待办",
            description = "审批指定 OA 待办任务（写操作）。用户明确要求通过/批准某条待办时直接调用本工具，参数 taskId 为待办编号；执行确认由平台时间线处理。",
            sideEffect = "write")
    public String approveOaTask(
            @ToolParam(value = "taskId", description = "待办任务 ID，如 T1001", required = false) String taskId) {
        String id = taskId != null && !taskId.isBlank() ? taskId.trim() : "unknown";
        return "已审批待办 " + id + "（模拟写操作）";
    }
}
